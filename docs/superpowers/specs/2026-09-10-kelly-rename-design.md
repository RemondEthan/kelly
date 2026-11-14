# Kelly 项目身份约定

## 背景
本仓库是独立桌面应用 Kelly，安装、数据、进程与其它客户端完全隔离。

## 范围

### 1. Maven 配置 (pom.xml)
- `artifactId` / `name`: kelly
- `jlink.image`: kelly-runtime
- 包与模块：`com.mordor.kelly`
- 主类：`Kelly`
- jpackage 应用名：`Kelly` / `kelly`

### 2. Java 模块系统
- `module-info.java` 模块名：`com.mordor.kelly`

### 3. 包名和类名
- 源码根：`src/main/java/com/mordor/kelly`
- 测试根：`src/test/java/com/mordor/kelly`
- 主类文件：`Kelly.java`

### 4. 图标
- `src/main/resources/icons/kelly.png`
- `src/main/resources/icons/kelly.icns`
- `src/main/resources/icons/kelly.ico`
- `src/main/resources/icons/kelly-alert.png`
- `src/main/jpackage/kelly.ico`

### 5. 窗口标题和应用名称
- 窗口标题：`kelly`
- jpackage：`Kelly` / `kelly`

### 6. 其它
- 历史文件头、线程名等字符串一律使用 kelly 前缀（如 `kelly-history-v1`）

## 成功标准
1. 项目成功编译
2. 所有测试通过
3. jpackage 成功生成 Kelly 应用
4. 应用窗口标题显示为 "kelly"
5. 应用图标正确显示
6. 源码与测试中不出现其它客户端的旧产品名
