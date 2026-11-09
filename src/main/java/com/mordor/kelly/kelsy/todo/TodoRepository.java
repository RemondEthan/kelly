package com.mordor.kelly.kelsy.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.storage.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.mordor.kelly.kelsy.storage.StorageException.Code.*;

/** Serialized JDBC access. Call on a worker, never on the JavaFX thread. */
public final class TodoRepository implements AutoCloseable {
    private final Connection db;
    private final Clock clock;
    private final Path root;
    private boolean closed;
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Input(String title, String descriptionMd, LocalDate dueDate, int priority, List<Todo.Source> sources) {
        public Input { sources = sources == null ? List.of() : List.copyOf(sources); }
    }
    public enum DueMode { ANY, DATED, UNDATED }
    public record Query(TodoStatus status, LocalDate from, LocalDate before, DueMode dueMode,
                        String keywords, int limit, String cursor) {
        public static Query open() { return new Query(TodoStatus.OPEN,null,null,DueMode.ANY,"",20,null); }
    }
    public record Page(List<Todo> items, String nextCursor) {}
    public TodoRepository(Path root) { this(root, Clock.systemDefaultZone()); }
    public TodoRepository(Path root, Clock clock) { this.db=DatabaseBootstrap.open(root,false); this.clock=clock; this.root=root.toAbsolutePath().normalize(); }
    private void check() { if(closed) throw new StorageException(CLOSED,"Task store is closed"); }
    private interface Work<T> { T run() throws Exception; }
    private <T> T transaction(Work<T> work) {
        check();
        try {
            try(var s=db.createStatement()) { s.execute("BEGIN IMMEDIATE"); }
            try {
                T value=work.run();
                try(var s=db.createStatement()) { s.execute("COMMIT"); }
                return value;
            } catch(Exception e) {
                try(var s=db.createStatement()) { s.execute("ROLLBACK"); }
                throw e;
            }
        } catch(StorageException e) { throw e; }
        catch(Exception e) { throw new StorageException(STORE_UNAVAILABLE,"Task transaction failed",e); }
    }
    private void validate(Input in) {
        if(in==null || in.title()==null || in.title().strip().isEmpty() || in.title().codePointCount(0,in.title().length())>200
            || in.descriptionMd()==null || in.descriptionMd().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144
            || in.priority()<0 || in.priority()>3 || in.sources().size()>20)
            throw new StorageException(INVALID_ARGUMENT,"Invalid task title, description, priority or sources");
        Set<UUID> seen=new HashSet<>();
        for(var source:in.sources()) {
            if(source.documentUid()==null || source.lastKnownPath()==null || !source.lastKnownPath().startsWith("knowledge/")
                || source.lastKnownPath().contains("..") || source.sourceHash()==null || !source.sourceHash().matches("[0-9a-f]{64}")
                || !seen.add(source.documentUid())) throw new StorageException(INVALID_ARGUMENT,"Invalid or duplicate source");
            var article=new com.mordor.kelly.kelsy.service.MarkdownDocumentStore(root).read(source.lastKnownPath(),source.sourceHash());
            if(!article.uid().equals(source.documentUid()))throw new StorageException(IDENTITY_CONFLICT,"Source article ID differs");
        }
    }
    public synchronized Todo create(Input in) {
        validate(in);
        return transaction(()-> {
            UUID uid=UUID.randomUUID(); long now=clock.millis();
            try(var p=db.prepareStatement("INSERT INTO todos(uid,title,description_md,due_date,priority,created_at,updated_at) VALUES(?,?,?,?,?,?,?)")) {
                p.setString(1,uid.toString()); p.setString(2,in.title().strip()); p.setString(3,in.descriptionMd());
                p.setString(4,in.dueDate()==null?null:in.dueDate().toString()); p.setInt(5,in.priority()); p.setLong(6,now);p.setLong(7,now);p.executeUpdate();
            }
            setSources(uid,in.sources()); maintain(uid,"created"); return get(uid);
        });
    }
    public synchronized Todo get(UUID uid) {
        check();
        try(var p=db.prepareStatement("SELECT * FROM todos WHERE uid=? AND deleted_at IS NULL")) {
            p.setString(1,uid.toString());
            try(var r=p.executeQuery()) { if(!r.next()) throw new StorageException(NOT_FOUND,"Task not found"); return row(r); }
        } catch(SQLException e) { throw new StorageException(STORE_UNAVAILABLE,"Cannot read task",e); }
    }
    private Todo row(ResultSet r) throws SQLException {
        UUID uid=UUID.fromString(r.getString("uid"));
        List<Todo.Source> sources=new ArrayList<>();
        try(var p=db.prepareStatement("SELECT * FROM todo_sources WHERE todo_id=? ORDER BY document_uid")) {
            p.setLong(1,r.getLong("id"));
            try(var sr=p.executeQuery()) { while(sr.next()) sources.add(new Todo.Source(UUID.fromString(sr.getString("document_uid")),sr.getString("last_known_path"),sr.getString("source_hash"),sr.getString("heading"))); }
        }
        String due=r.getString("due_date");
        return new Todo(uid,r.getString("title"),r.getString("description_md"),TodoStatus.valueOf(r.getString("status").toUpperCase(Locale.ROOT)),due==null?null:LocalDate.parse(due),r.getInt("priority"),Instant.ofEpochMilli(r.getLong("created_at")),Instant.ofEpochMilli(r.getLong("updated_at")),instant(r,"completed_at"),instant(r,"deleted_at"),r.getLong("version"),sources);
    }
    private static Instant instant(ResultSet r,String name) throws SQLException { long t=r.getLong(name);return r.wasNull()?null:Instant.ofEpochMilli(t); }
    public synchronized Todo edit(UUID uid,long version,Input in) { validate(in);return mutate(uid,version,"edited",in,null,false); }
    public synchronized Todo complete(UUID uid,long version) { return mutate(uid,version,"completed",null,TodoStatus.CLOSED,false); }
    public synchronized Todo reopen(UUID uid,long version) { return mutate(uid,version,"reopened",null,TodoStatus.OPEN,false); }
    public synchronized Todo delete(UUID uid,long version) { return mutate(uid,version,"deleted",null,null,true); }
    private Todo mutate(UUID uid,long version,String event,Input in,TodoStatus status,boolean delete) {
        if(version<1) throw new StorageException(INVALID_ARGUMENT,"expectedVersion must be positive");
        return transaction(()-> {
            Todo old=get(uid);
            if(old.version()!=version) throw new StorageException(CONFLICT,"Task version changed; read it again");
            if(status!=null && old.status()==status) return old;
            long now=clock.millis();
            try(var p=db.prepareStatement("UPDATE todos SET title=?,description_md=?,due_date=?,priority=?,status=?,completed_at=?,deleted_at=?,updated_at=?,version=version+1 WHERE uid=? AND version=?")) {
                p.setString(1,in==null?old.title():in.title().strip());p.setString(2,in==null?old.descriptionMd():in.descriptionMd());
                LocalDate due=in==null?old.dueDate():in.dueDate();p.setString(3,due==null?null:due.toString());p.setInt(4,in==null?old.priority():in.priority());
                TodoStatus next=status==null?old.status():status; p.setString(5,next.name().toLowerCase(Locale.ROOT));
                Instant done=status==TodoStatus.CLOSED?Instant.ofEpochMilli(now):status==TodoStatus.OPEN?null:old.completedAt();
                p.setObject(6,done==null?null:done.toEpochMilli());p.setObject(7,delete?now:null);p.setLong(8,now);p.setString(9,uid.toString());p.setLong(10,version);
                if(p.executeUpdate()!=1) throw new StorageException(CONFLICT,"Task changed");
            }
            if(in!=null) setSources(uid,in.sources());
            Todo value=raw(uid); maintain(uid,event);return value;
        });
    }
    private Todo raw(UUID uid) throws SQLException {
        try(var p=db.prepareStatement("SELECT * FROM todos WHERE uid=?")) { p.setString(1,uid.toString());try(var r=p.executeQuery()){r.next();return row(r);} }
    }
    private void setSources(UUID uid,List<Todo.Source> sources) throws SQLException {
        try(var p=db.prepareStatement("DELETE FROM todo_sources WHERE todo_id=(SELECT id FROM todos WHERE uid=?)")){p.setString(1,uid.toString());p.executeUpdate();}
        for(var source:sources) try(var p=db.prepareStatement("INSERT INTO todo_sources VALUES((SELECT id FROM todos WHERE uid=?),?,?,?,?)")) {
            p.setString(1,uid.toString());p.setString(2,source.documentUid().toString());p.setString(3,source.lastKnownPath());p.setString(4,source.sourceHash());p.setString(5,source.heading());p.executeUpdate();
        }
    }
    private void maintain(UUID uid,String event) throws Exception {
        Todo t=raw(uid);
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("uid",uid.toString());snapshot.put("title",t.title());snapshot.put("descriptionMd",t.descriptionMd());snapshot.put("status",t.status().name());snapshot.put("dueDate",t.dueDate()==null?null:t.dueDate().toString());snapshot.put("priority",t.priority());snapshot.put("version",t.version());snapshot.put("createdAt",t.createdAt().toString());snapshot.put("updatedAt",t.updatedAt().toString());snapshot.put("completedAt",t.completedAt()==null?null:t.completedAt().toString());snapshot.put("deletedAt",t.deletedAt()==null?null:t.deletedAt().toString());snapshot.put("sources",t.sources().stream().map(s->Map.of("documentUid",s.documentUid().toString(),"path",s.lastKnownPath(),"hash",s.sourceHash(),"heading",s.heading()==null?"":s.heading())).toList());
        try(var p=db.prepareStatement("INSERT INTO todo_events(todo_id,version,event_type,snapshot_json,created_at) VALUES((SELECT id FROM todos WHERE uid=?),?,?,?,?)")) {
            p.setString(1,uid.toString());p.setLong(2,t.version());p.setString(3,event);p.setString(4,JSON.writeValueAsString(snapshot));p.setLong(5,clock.millis());p.executeUpdate();
        }
        if(t.deletedAt()!=null) { try(var p=db.prepareStatement("DELETE FROM todo_search_content WHERE id=(SELECT id FROM todos WHERE uid=?)")){p.setString(1,uid.toString());p.executeUpdate();} }
        else try(var p=db.prepareStatement("INSERT INTO todo_search_content VALUES((SELECT id FROM todos WHERE uid=?),?,?) ON CONFLICT(id) DO UPDATE SET title_terms=excluded.title_terms,body_terms=excluded.body_terms")) {
            p.setString(1,uid.toString());p.setString(2,SearchTerms.index(t.title()));p.setString(3,SearchTerms.index(t.descriptionMd()));p.executeUpdate();
        }
    }
    public synchronized Page list(Query q) {
        check();
        if(q==null || q.limit()<1 || q.limit()>50 || (q.dueMode()==DueMode.UNDATED && (q.from()!=null||q.before()!=null)) || (q.from()!=null&&q.before()!=null&&!q.from().isBefore(q.before())) || (q.keywords()!=null&&q.keywords().length()>400)) throw new StorageException(INVALID_ARGUMENT,"Invalid task filters");
        String match=SearchTerms.match(q.keywords());boolean search=!match.isBlank();
        if(q.keywords()!=null&&!q.keywords().isBlank()&&!search)throw new StorageException(INVALID_ARGUMENT,"Provide searchable keywords");
        StringBuilder sql=new StringBuilder("SELECT t.*, "+(search?"bm25(todo_fts)":"0.0")+" AS rank FROM todos t "+(search?"JOIN todo_fts ON todo_fts.rowid=t.id ":"")+"WHERE t.deleted_at IS NULL");
        List<Object> args=new ArrayList<>();
        if(search) {sql.append(" AND todo_fts MATCH ?");args.add(match);}
        if(q.status()!=null){sql.append(" AND t.status=?");args.add(q.status().name().toLowerCase(Locale.ROOT));}
        if(q.from()!=null){sql.append(" AND t.due_date>=?");args.add(q.from().toString());}
        if(q.before()!=null){sql.append(" AND t.due_date<?");args.add(q.before().toString());}
        if(q.dueMode()==DueMode.DATED)sql.append(" AND t.due_date IS NOT NULL");
        if(q.dueMode()==DueMode.UNDATED)sql.append(" AND t.due_date IS NULL");
        String fingerprint=Objects.toString(q.status())+"|"+q.from()+"|"+q.before()+"|"+q.dueMode()+"|"+Objects.toString(q.keywords(),"");
        if(q.cursor()!=null) {
            try {
                var cursor=JSON.readTree(Base64.getUrlDecoder().decode(q.cursor()));
                if(!cursor.path("filter").asText().equals(fingerprint))throw new IllegalArgumentException();
                long id=cursor.path("id").asLong();
                if(search){sql.append(" AND (bm25(todo_fts)>? OR (bm25(todo_fts)=? AND t.id>?))");double rank=cursor.path("rank").asDouble();args.add(rank);args.add(rank);args.add(id);}
                else {sql.append(" AND (COALESCE(t.due_date,'9999-99-99')>? OR (COALESCE(t.due_date,'9999-99-99')=? AND t.id>?))");String due=cursor.path("due").asText();args.add(due);args.add(due);args.add(id);}
            } catch(Exception e){throw new StorageException(INVALID_ARGUMENT,"Invalid pagination cursor");}
        }
        sql.append(search?" ORDER BY rank,t.id":" ORDER BY COALESCE(t.due_date,'9999-99-99'),t.id");sql.append(" LIMIT ?");args.add(q.limit()+1);
        try(var p=db.prepareStatement(sql.toString())) {
            for(int i=0;i<args.size();i++)p.setObject(i+1,args.get(i));
            List<Todo> items=new ArrayList<>();String cursor=null;boolean more=false;
            try(var r=p.executeQuery()){while(r.next()){
                if(items.size()==q.limit()){more=true;break;}
                items.add(row(r));
                cursor=Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(Map.of("filter",fingerprint,"id",r.getLong("id"),"rank",r.getDouble("rank"),"due",r.getString("due_date")==null?"9999-99-99":r.getString("due_date"))));
            }}
            return new Page(List.copyOf(items),more?cursor:null);
        }catch(Exception e){if(e instanceof StorageException se)throw se;throw new StorageException(STORE_UNAVAILABLE,"Cannot query tasks",e);}
    }
    public record Pending(UUID uid, LocalDate day, List<TodoCard> todos, EnumSet<ReminderSlot> slots) {}
    private List<TodoCard> window(LocalDate today) throws SQLException {
        List<TodoCard> cards=new ArrayList<>();
        try(var p=db.prepareStatement("SELECT * FROM todos WHERE deleted_at IS NULL AND status='open' AND due_date<=?")){
            p.setString(1,today.plusDays(3).toString());try(var r=p.executeQuery()){while(r.next())cards.add(row(r).card());}
        }
        return TodoReminderPolicy.sort(cards);
    }
    public synchronized Optional<Pending> claim(LocalDateTime now) {
        return transaction(()-> {
            // Resume an unacknowledged batch before claiming another slot.
            try(var p=db.prepareStatement("SELECT uid,day,payload_json FROM reminder_batches WHERE state='pending' ORDER BY created_at,uid")){try(var r=p.executeQuery()){
                while(r.next()){
                    UUID uid=UUID.fromString(r.getString(1));
                    Set<String> wanted=new HashSet<>();for(var node:JSON.readTree(r.getString(3)).path("tasks"))wanted.add("todo:"+node.path("uid").asText());
                    List<TodoCard> cards=window(now.toLocalDate()).stream().filter(t->wanted.contains(t.relativePath())).toList();
                    if(cards.isEmpty()){finishBatch(uid,"cancelled");continue;}
                    return Optional.of(new Pending(uid,LocalDate.parse(r.getString(2)),cards,slots(uid)));
                }
            }}
            List<TodoCard> cards=window(now.toLocalDate());if(cards.isEmpty())return Optional.empty();
            EnumSet<ReminderSlot> due=EnumSet.of(ReminderSlot.LOGIN);
            if(!now.toLocalTime().isBefore(LocalTime.of(10,0)))due.add(ReminderSlot.TEN);
            if(!now.toLocalTime().isBefore(LocalTime.of(14,0)))due.add(ReminderSlot.FOURTEEN);
            try(var p=db.prepareStatement("SELECT slot FROM reminder_slots WHERE day=?")){p.setString(1,now.toLocalDate().toString());try(var r=p.executeQuery()){while(r.next())due.remove(ReminderSlot.valueOf(r.getString(1)));}}
            if(due.isEmpty())return Optional.empty();
            UUID uid=UUID.randomUUID();
            try(var p=db.prepareStatement("INSERT INTO reminder_batches(uid,day,state,payload_json,created_at) VALUES(?,?,'pending',?,?)")){
                p.setString(1,uid.toString());p.setString(2,now.toLocalDate().toString());p.setString(3,JSON.writeValueAsString(Map.of("formatVersion",1,"tasks",cards.stream().map(card->{Todo t=get(UUID.fromString(card.relativePath().substring(5)));return Map.of("uid",t.uid().toString(),"version",t.version());}).toList())));p.setLong(4,clock.millis());p.executeUpdate();
            }
            for(var slot:due)try(var p=db.prepareStatement("INSERT INTO reminder_slots VALUES(?,?,?)")){p.setString(1,now.toLocalDate().toString());p.setString(2,slot.name());p.setString(3,uid.toString());p.executeUpdate();}
            return Optional.of(new Pending(uid,now.toLocalDate(),cards,due));
        });
    }
    private EnumSet<ReminderSlot> slots(UUID uid)throws SQLException{
        EnumSet<ReminderSlot> result=EnumSet.noneOf(ReminderSlot.class);
        try(var p=db.prepareStatement("SELECT slot FROM reminder_slots WHERE batch_uid=?")){p.setString(1,uid.toString());try(var r=p.executeQuery()){while(r.next())result.add(ReminderSlot.valueOf(r.getString(1)));}}return result;
    }
    private void finishBatch(UUID uid,String state)throws SQLException{
        try(var p=db.prepareStatement("UPDATE reminder_batches SET state=?,delivered_at=? WHERE uid=? AND state='pending'")){p.setString(1,state);p.setObject(2,state.equals("delivered")?clock.millis():null);p.setString(3,uid.toString());p.executeUpdate();}
    }
    public synchronized void acknowledge(UUID uid){transaction(()->{
        try(var p=db.prepareStatement("SELECT state FROM reminder_batches WHERE uid=?")){
            p.setString(1,uid.toString());try(var r=p.executeQuery()){
                if(!r.next())throw new StorageException(NOT_FOUND,"Reminder batch not found");
                if(r.getString(1).equals("cancelled"))throw new StorageException(CONFLICT,"Reminder batch cancelled");
            }
        }
        finishBatch(uid,"delivered");return null;
    });}
    public synchronized void backup(Path destination){check();try(var p=db.prepareStatement("VACUUM INTO ?")){p.setString(1,destination.toAbsolutePath().toString());p.execute();}catch(SQLException e){throw new StorageException(STORE_UNAVAILABLE,"Backup failed",e);}}
    @Override public synchronized void close(){if(!closed){closed=true;try{db.close();}catch(SQLException e){throw new StorageException(STORE_UNAVAILABLE,"Cannot close tasks",e);}}}
}
