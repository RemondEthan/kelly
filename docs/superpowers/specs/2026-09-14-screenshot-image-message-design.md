# 截图粘贴与图片消息（微信式双层）

## 背景

Kelly 聊天目前是纯文本：`Message.content` 为字符串，`ChatController.send` / `ImClient.sendChat` 只走加密 `type=text`。`InputBar` 回形针仍是「文件传输未实现」。JavaFX `TextField` 默认粘贴只处理文字。

系统截图（macOS `Cmd+Shift+4` / Windows `Win+Shift+S` 等）把 PNG 放进系统剪贴板，用户用 `Ctrl+V` / `Cmd+V` 期望挂到输入栏再发送。头像通道按约 32KB 密文丢弃来压缩，不能承载截图。KServer 当前只识别并转发 `register` / `text`，且历史设计里文件传输是非目标。

本规格实现完整图片消息：粘贴草稿、预览气泡、加密分片传原图、历史落盘；KServer 增加对图片类型的无感转发。

## 目标与非目标

**目标**

- 输入框聚焦时，剪贴板里的位图（及剪贴板中的图片文件）用 `Ctrl/Cmd+V` 进入草稿，不插入文件路径等垃圾文本。
- 草稿显示缩略图，可配文字说明，发送/回车才发出；Esc 或草稿关闭钮取消。
- 发出后对端立刻看到压缩预览气泡；后台传原图；点击可看原图（未齐则「查看原图」等待/重试）。
- 本地历史保存元数据 + 预览文件 + 收齐后的原图文件；旧纯文本历史仍能解码。
- 体积上限：原图超过 **20MB** 拒绝发送并提示。预览 JPEG 目标约 **200–400KB**。分片明文约 **48KB**。
- 同一时刻最多一张草稿图。

**非目标**

- 通用文件传输（非图片）。回形针本里程碑可继续占位；不要求选图。
- 拖拽入框、多图草稿、群内已读回执。
- 对象存储 / HTTP 旁路。
- **秘书（Kelsy）看图**：图片只走对端 `PEER` 路径。带图时不把图交给 Ask/Find；不把预览或原图塞进助手上下文。说明文字若单独作为文本发送，仍按现有 `KelsySendRouter` 规则（本功能默认：有图则整条作为图片消息发给对端，不拆成秘书问题）。
- 不改动头像 32KB 策略。

## 架构

```
剪贴板 Image/文件
    → ClipboardImageReader（规范 PNG 字节 + MIME + sha256）
    → InputBar 草稿（缩略图 + 说明文字）
    → Send：本地写 pending 原图/预览
         → ImClient.sendImageMeta（加密 JSON：id/caption/mime/bytes/sha256/previewJpeg）
         → ImClient.sendImageChunks（加密 JSON：id,i,n,data）
    → KServer：type=image | image_chunk 与 text 一样转发给同 IM_CODE 他人
    → 对端：先出预览气泡，分片写入 ~/.kelly/media/<imCode>/<id>.part → 校验 sha256 → 原图文件
    → MessageBubble：预览 ImageView；点击打开原图查看器
```

KServer 仍不解密、不落盘。心跳维持现有客户端 5 秒 ping，避免长传被 15 秒超时踢掉。

## 粘贴与草稿

1. 在 `InputBar` 的 `textField` 上 `addEventFilter(KEY_PRESSED)`：检测粘贴快捷键（macOS `Meta+V`，其它 `Ctrl+V`）。
2. 若 `Clipboard.hasImage()`：取出 `Image`，转 `BufferedImage` 再 `ImageIO` 写成 PNG 字节作为原图；`consume` 事件。
3. 否则若 `hasFiles()` 且第一个文件扩展名为 png/jpg/jpeg/gif/webp：读文件字节；`consume`。
4. 若只有文字：不拦截，保持现有粘贴。
5. 图+假文字同时存在时：有图则走图，不粘贴文字。
6. 草稿：输入栏上方缩略图条（约 72px 高）+ 关闭。`textField` 继续写说明。空说明允许只发图。发送按钮：有草稿或非空文字即可启用。
7. 发送成功后清草稿与文字。发送被拒（忙碌/脱机）保留草稿。
8. 脱机登录：与文本一样拒绝发送（`OFFLINE_REJECT_HINT`）。

## 协议（明文结构，整体再 AES 加密后放 data.content）

沿用现有 `{"type":"...","data":{"content":<密文>,"username":...}}`。密文解密后为 UTF-8 JSON，不是裸二进制。

**`type=image`（元数据 + 预览）**

```json
{
  "v": 1,
  "id": "<uuid>",
  "caption": "<说明，可空>",
  "mime": "image/png",
  "bytes": 1234567,
  "sha256": "<hex>",
  "previewJpeg": "<standard base64>"
}
```

`previewJpeg` 由发送端从原图生成（最长边约 1280，JPEG 质量约 0.8，体积尽量落在 200–400KB；超 400KB 再降质量/边长，保底不低于边长 480）。

**`type=image_chunk`**

```json
{
  "id": "<uuid>",
  "i": 0,
  "n": 20,
  "data": "<该片原图字节的 standard base64>"
}
```

片序从 0 到 n-1。每片解码后明文约 48KB。发送端按序发送；接收端按 `id` 缓冲，允许乱序到达。

收齐后 sha256 与元数据一致才改名为原图文件；失败保留 `.part` 并可点「查看原图」触发（本里程碑仅提示失败，不自动重传整图；重传可作为后续）。

未知 `type` 继续忽略。KServer `handleMessage` 增加 `image`、`image_chunk` 分支，实现与 `handleText` 相同：未注册丢弃，已注册原样转发给同组其它连接。

不经过 `type=avatar`。

## 数据模型与历史

`Message` 增加字段（旧记录缺省）：

- `kind`：`TEXT`（默认）| `IMAGE`
- `mediaId`：图片 uuid，文本为空
- `caption`：展示用说明；`content` 对图片消息可等于 caption 或空，避免破坏只读 `content` 的旧 UI 路径时，气泡以 `kind` 为准
- `previewRel` / `originalRel`：相对 `~/.kelly/media/<imCode>/` 的文件名（如 `<id>.jpg`、`<id>.png`）

`HistoryCodec`：新字段可选写出；解码时无 `kind` 视为 `TEXT`。禁止把 JPEG/PNG 字节写进 JSON。

媒体目录与聊天档案同一用户数据根，按 `imCode` 分子目录。发送端在发出元数据前先写本地原图与预览，失败则不发送。

## UI

- `MessageBubble`：`IMAGE` 用 `ImageView` 显示预览（限制最大宽与现有气泡宽绑定），caption 在图下。点击预览打开简易查看器（新 `Stage` 或对话框）：原图文件存在则显示原图，否则显示预览并文案「原图传输中…」或「原图不完整」。
- 文本选择/复制逻辑不套在图片上；caption 仍可用现有可选文本。
- 本里程碑不把图片交给 Kelsy 气泡或知识库。

## 错误处理

| 情况 | 行为 |
| --- | --- |
| 剪贴板无法解码 | 短 hint，不 consume 失败后可退回文字粘贴 |
| 原图 > 20MB | 不进草稿，hint 说明超限 |
| 未注册/脱机 | 与文本相同拒绝 |
| 单片解密失败 | 该 id 标记失败，预览仍在 |
| sha256 不符 | 不把 `.part` 当原图 |
| 预览缺失 | 气泡占位 + caption |
| 对端旧客户端 | 忽略未知 type，收不到图（可接受） |

## 测试

- `ClipboardImageReader`：假 PNG 字节往返、超限拒绝、非图文件忽略。
- 分片：切分/合并、乱序、缺片、sha256 失败。
- `HistoryCodec`：旧 JSON 仍解码为 TEXT；IMAGE 记录不把二进制写入 content。
- `Protocol`：image / image_chunk 组包与 quote。
- `InputBar`：有草稿时发送启用；粘贴图 consume（用可注入的剪贴板门面，避免测真实系统剪贴板）。
- KServer：image / image_chunk 转发到同组其它连接，不发给自己。
- 不测真实 OS 截图工具；用剪贴板门面注入 `Image` 或文件列表即可。

## 仓库边界

- **kelly**：粘贴、草稿、协议客户端、气泡、历史、媒体文件。
- **kserver**：仅增加两种 type 的原样转发；不解析 `data.content`。

未拍板前不改业务代码。实现计划在 spec 审阅通过后再写。
