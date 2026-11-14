# 加密凭据库设计（提案，尚未实现）

## 目标与边界

知识文章继续采用 Markdown + 可重建 SQLite 索引；密码、API Token、恢复码进入每用户独立的 `.kelly-vault.db`。该库是正式数据，不能当索引删除或重建。不进入 `.kelly-index.db`、Markdown、模型上下文、会话历史或诊断日志。

本设计保护数据库及备份被复制后的机密性和密文完整性；不能抵御已控制解锁进程的恶意程序、键盘记录器或内存读取。SQLite 文件仍暴露记录数量、密文大小等信息。条目标题、账号、网址也加密。

## 加密与密钥

- 使用成熟密码库，禁止自行设计算法。Java JCE 使用 `AES/GCM/NoPadding`：256 位密钥、96 位 nonce、128 位认证标签。
- 首次创建随机 256 位库密钥 DEK；每次条目加密及密钥封装都用 SecureRandom 生成新 nonce，绝不复用同一密钥下的 nonce。nonce 与密文一并保存。
- 主密码通过 Argon2id v1.3 派生 256 位 KEK；初始参数 m=65536 KiB、t=3、p=4、随机 16 字节 salt。上线前按目标设备测量延时，可提高成本；参数随密钥槽保存，升级时重新封装 DEK。读入参数必须校验上限，避免恶意备份耗尽内存。
- KEK 仅用来以 AES-GCM 封装 DEK；DEK 用于加密条目。主密码、KEK、明文 DEK 不写磁盘。通过解封装认证判断密码正确，不另外存可逆密码或快速密码校验值。
- AAD 使用确定性编码，绑定格式版本、vault_id、用途；条目另外绑定条目 ID、修订号和 key_version，避免跨条目替换。AAD 不放秘密。
- 更换主密码：生成新 salt/KEK/nonce，事务内重新封装原 DEK，不改写全部条目。旧备份仍可用旧主密码解锁；改密码无法撤销已泄漏备份。
- 数据密钥轮换是独立维护操作：新 DEK 重加密全部条目并原子切换版本，失败回滚。它同样不能撤销旧备份。
- 第一版只支持主密码。后续可加操作系统钥匙串的独立解锁槽，须显式启用；数据库与普通配置文件不得保存明文解锁密钥。
- 可选独立随机恢复密钥，单独封装同一 DEK，首次展示并要求用户离线保存。没有恢复槽且主密码遗失则无法恢复；不要用账户密码重置绕过加密。

## 数据结构

独立 VaultDatabaseBootstrap；不得复用现有 DatabaseBootstrap 的 index/data 布尔分支。

```sql
CREATE TABLE vault_meta (
  singleton INTEGER PRIMARY KEY CHECK(singleton=1),
  vault_id TEXT NOT NULL UNIQUE,
  format_version INTEGER NOT NULL,
  active_key_version INTEGER NOT NULL
);
CREATE TABLE key_slots (
  id TEXT PRIMARY KEY,
  slot_type TEXT NOT NULL,
  key_version INTEGER NOT NULL,
  kdf_name TEXT,
  kdf_params_json TEXT,
  salt BLOB,
  wrap_nonce BLOB NOT NULL CHECK(length(wrap_nonce)=12),
  wrapped_dek BLOB NOT NULL
);
CREATE TABLE vault_items (
  id TEXT PRIMARY KEY,
  revision INTEGER NOT NULL CHECK(revision>0),
  key_version INTEGER NOT NULL,
  nonce BLOB NOT NULL CHECK(length(nonce)=12),
  ciphertext BLOB NOT NULL
);
PRAGMA user_version=1;
```

密文包含 UTF-8 JSON：payloadVersion、type、title、username、password/token、urls、notes、tags、createdAt、updatedAt。GCM 标签随密文保存。限制条目与字段大小；密文和 JSON 严格版本化校验。更新必须带 expectedRevision，用条件 UPDATE 防止覆盖并发修改。SQL 仅接收密文，确保 journal/WAL/临时文件不会因本功能写入明文。

第一版解锁后在内存解密目录并搜索，锁定时不可搜索。条目详情按需解密；不建 FTS、明文缓存或持久化盲索引。未来条目很多时再评估性能和检索泄漏。

## 用户与助手交互

1. 用户说“帮我保存数据库密码”，助手只触发本地凭据编辑窗口；用户在独立密码字段输入，字段不经过聊天消息或 AgentTool 参数。
2. 保存后工具只返回 opaque reference 与成功状态；默认不返回标题、账号、域名或任何秘密。若用户已把秘密发到聊天，保存不能消除既有会话或上游传输副本，界面应提示这一点。
3. 用户请求查看/复制时，在本地界面解锁并选择条目。密码默认遮挡，查看或复制需本次明确操作；助手工具不能返回明文。
4. 初版助手工具限 `vault_open`（打开面板/编辑窗口）和 `vault_status`（是否初始化/锁定）。不提供通用 `secret_get`；不让模型枚举条目。
5. 后续如支持代登录，工具只接受凭据引用与明确目标，由本地执行器按经用户授权的操作注入秘密；绑定准确 origin/协议/端口及动作，不允许模型选择任意外发目标。授权不等于把秘密交给模型。

## 生命周期与实现模块

- VaultCrypto：派生、封装、AEAD；可注入随机源仅用于测试。
- VaultRepository：密文 CRUD、版本冲突、SQLite 在线快照；所有查询使用绑定参数。
- VaultSession：LOCKED/UNLOCKED 状态、闲置 5 分钟自动锁定；系统锁屏、退出账号、关闭应用立即锁定。锁定时关闭敏感窗口、废弃缓存与能力句柄，尽力覆盖可变字节数组。
- VaultService：协调会话、存储和本地授权。VaultPane：独立 JavaFX 密码管理界面。
- Java String/JavaFX 控件不能保证彻底擦除内存；限制明文生命周期，不宣称 Java 堆里绝无残留。崩溃上报、异常信息、toString、遥测、工具追踪禁止包含敏感字段。
- 复制后约 30 秒，仅当剪贴板仍为本次写入内容时清除；明确剪贴板历史/其他程序可能留存副本。锁定后到期清理仍须生效。
- 主密码失败实行 UI 退避，但这不能阻止离线猜测；核心防线仍是强主密码与 Argon2id。

## 备份、删除与迁移

现有 HybridBackupService 只备份 Markdown 和 `.kelly-data.db`，不会自动包含新库。第一版提供独立加密库导出；可在锁定状态使用 SQLite 在线备份 API 获得一致快照，不直接复制正在使用的 db 文件，也不导出明文 JSON/CSV。快照包含密钥槽、盐和 KDF 参数，不包含明文密钥。

恢复先在隔离目录检查大小、格式、schema、KDF 参数边界与 SQLite 完整性，用户解锁成功并确认后才接入；不覆盖现有库。普通备份清单中的 hash 只能检测无意损坏，不能提供抗篡改认证。AEAD 检测条目篡改，但单靠本地文件无法检测整库被替换为旧快照，也无法防止删除记录；首版明确不保证抗回滚。

删除条目不等于物理擦除历史页、SSD 或旧备份；仅保证正常 API 不再返回该条目。泄漏凭据需要到对应服务撤销/轮换。

不自动把现有 Markdown 中疑似密码复制入库再删除。迁移需用户选中和本地确认，写入后回读验证，再处理原文件；原索引、WAL、会话、备份可能仍留痕，迁移不能保证擦除。对已暴露凭据建议轮换。

## 验收条件

- 正确主密码可读回；错误密码、错误 AAD、篡改密文/nonce/认证标签均拒绝，绝不返回部分明文。
- 同一明文多次保存产生不同密文；并发更新与换主密码失败不会产生半写状态。
- 用固定测试秘密检查数据库、WAL、备份、索引、日志、会话与工具返回值，不出现测试明文。
- 锁定、退出与系统锁屏阻止读取；已排队操作和详情窗口同步失效。
- 备份恢复、旧版本迁移、KDF 资源上限、恢复密钥与错误格式均有集成测试；密码学实现运行公开测试向量。

## 依据

- OWASP Cryptographic Storage Cheat Sheet：https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html
- OWASP Key Management Cheat Sheet：https://cheatsheetseries.owasp.org/cheatsheets/Key_Management_Cheat_Sheet.html
- RFC 9106 Argon2：https://www.rfc-editor.org/rfc/rfc9106.html
