# Novum 品牌资源

`novum_brand_banner.svg` 是唯一编辑母版：深色背景、`novum-mark` 放映机标识和
路径化的 `NOVUM` 字标。它不引用字体、外部图片或网络资源。更新标识时修改
`novum-mark`，所有应用图标和 TV banner 都从它输出，避免各密度版本走样。

`generate.cjs` 是可选的美术资源生成工具，不参与 APK 日常构建。需要 Node.js 和
`sharp` 0.35.5；可以在仓库外安装 Sharp，再通过 `NODE_PATH` 指定它的
`node_modules` 目录。例如 PowerShell：

```powershell
npm install --prefix "$env:TEMP/novum-brand-tools" --no-save --package-lock=false sharp@0.35.5
$env:NODE_PATH = "$env:TEMP/novum-brand-tools/node_modules"
node tools/brand/generate.cjs
```

脚本重建以下已提交资源：

- `app/src/main/res/drawable/ic_launcher_foreground.xml`：108dp 自适应图标前景，
  透明片孔和播放三角也适用于主题单色图标。
- `app/src/main/res/drawable/ic_launcher_background.xml`：纯色自适应图标背景。
- `mipmap-anydpi-v26/ic_launcher.xml` 和 `ic_launcher_round.xml`：普通与圆形
  自适应图标入口，共用同一前景、背景和单色层。
- `mipmap-mdpi` 至 `mipmap-xxxhdpi` 中的 `ic_launcher.png` 和
  `ic_launcher_round.png`：普通／圆形桌面使用同一标识，保留项目既有
  80/120/160/240/320px 口径，兼容 API 24–25 与旧桌面。
- 同目录中的 `app_banner.png`：保持 16:9，以及项目既有
  160×90 / 240×135 / 320×180 / 480×270 / 640×360px 五档尺寸。

`build/brand-preview/icon.png`、`icon-round.png`、`banner.png` 和 `suite.png`
是一套本地检查预览，不加入 Git。`suite.png` 同时展示普通图标、圆形图标和 TV banner。
生成后检查最小图标的可辨识性、TV banner 的字标、透明边缘，以及 Android
自适应图标的圆形/圆角裁切。PNG 是旧桌面和 TV launcher 的直接兼容资源，
不依赖系统能否读取 SVG；应用打包也不包含编辑母版。
