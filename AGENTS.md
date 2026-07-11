使用中文回答
在每次回答前都输出本次会话使用了哪些技能和哪些mcp

项目规则：
- 修改已有代码前必须先阅读相关实现，禁止基于猜测改动。
- 本地构建产物 APK 最多保留 1 个；生成新 APK 后必须清理旧 APK，除非用户明确要求保留用于对比。
- `app/src/main/assets-app/template.apk` 是打包模板资产，不按本地构建产物 APK 处理。
- `app/src/main/assets-app/template.apk` 是 App 内项目打包的基础模板；App 构建时会将其作为 asset 打入安装包，项目打包时会读取它作为基础 APK。
- 该模板由 `./gradlew :app:assembleInrtRelease` 在完成后，自动从 INRT universal APK 复制生成；仅执行 `assembleAppDebug`、`assembleAppRelease` 或 `assembleInrtDebug` 不会生成模板。
- 修改影响 INRT 打包基础能力的代码后，必须重新执行 `assembleInrtRelease` 并更新模板分发产物。模板超过普通 Git 单文件限制时，使用 Git LFS 或受控下载机制管理，禁止只依赖本地忽略文件。
- `assembleInrtRelease` 生成的是 INRT 模板包，不是正常 AutoJs6 安装包。其输出路径为 `app/build/outputs/apk/inrt/release/inrt-v<版本>-universal.apk`，包名为 `org.autojs.autojs6.inrt`，禁止作为主应用安装包交付。
- 正常 AutoJs6 安装包必须使用 `./gradlew :app:assembleAppDebug` 或 `./gradlew :app:assembleAppRelease` 构建。输出应位于 `app/build/outputs/apk/app/debug/` 或 `app/build/outputs/apk/app/release/`，文件名以 `autojs6-v` 开头，包名必须为 `org.autojs.autojs6`。
- 标准主应用交付流程：先执行 `./gradlew :app:assembleInrtRelease` 更新 `template.apk`；再执行 `./gradlew :app:clean :app:assembleAppDebug`（或 `assembleAppRelease`）生成正常安装包。`clean` 不会删除 `app/src/main/assets-app/template.apk`。
- 主应用构建可能同时生成多个 ABI APK。除非用户明确指定某个 ABI，默认仅保留并交付 `autojs6-v<版本>-universal.apk`，其余 APK 必须删除，以满足本地构建产物最多保留 1 个的规则。
- 交付 APK 前必须核验：文件位于 `apk/app/<buildType>/`、文件名以 `autojs6-v` 开头、applicationId 为 `org.autojs.autojs6`。任何位于 `apk/inrt/`、文件名以 `inrt-v` 开头或 applicationId 为 `org.autojs.autojs6.inrt` 的 APK 都只能作为模板中间产物，不得当作主应用交付。
