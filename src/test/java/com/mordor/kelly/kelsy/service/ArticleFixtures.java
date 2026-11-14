package com.mordor.kelly.kelsy.service;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** New-format test fixture writer; production has no legacy import path. */
public final class ArticleFixtures {
    private ArticleFixtures() {}
    public static Path writeString(Path path,CharSequence text,OpenOption... options)throws IOException {
        String raw=text.toString();String normalized=path.toString().replace('\\','/');
        if(normalized.contains("/knowledge/")&&!normalized.endsWith("/KNOWLEDGE.md")&&!normalized.contains("/knowledge/todos/")&&!raw.startsWith("---\n")){
            CardFields fields=CardFields.parse(normalized,raw);
            String title=path.getFileName().toString().replace(".md","");
            raw="---\nid: \""+UUID.nameUUIDFromBytes(path.toString().getBytes(StandardCharsets.UTF_8))+"\"\ntype: article\ntitle: \""+title.replace("\"","\\\"")+"\"\n"+(fields.date()==null||fields.date().isBlank()?"":"date: \""+fields.date()+"\"\n")+"---\n"+raw;
        }
        return Files.writeString(path,raw,options);
    }
}
