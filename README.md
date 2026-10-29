# L-Moment

Android 端离线色彩风格处理应用。所有处理在本机完成，不联网、不上传图片。

- 色彩风格码：把一组调色参数编码成短码，可复制分享、扫码或粘贴导入。
- 多路渲染：3D LUT 实时预览、参数化风格链、以及可选的轻量模型推理。
- 降级设计：任何一条处理链路缺少所需模型文件时自动回退，不影响其余功能。

## 声明

1. 本项目为个人学习与实验项目，与 Leica、小米及其关联公司**无任何隶属、授权或背书关系**。代码与文档中出现的第三方商标仅用于描述兼容性或格式约定，不构成来源标识。
2. 仓库只包含自研源码、自产 LUT 与自产蒸馏模型。**部分运行时模型文件不随仓库分发**，也不提供获取途径；缺失时对应链路自动降级。
3. 仅供个人学习研究，不得商用，不得再分发本项目的二进制构建产物。

## 构建

- JDK 17，Android SDK 34（`compileSdk` / `targetSdk` 均为 34），`minSdk` 26
- Gradle 8.2（仓库不含 wrapper，使用本机 Gradle）
- NDK + CMake 3.22.1（原生层编译 LibRaw 与 JNI 封装）
- 仅 `arm64-v8a`

```bash
gradle assembleDebug
```

原生层内置 LibRaw 静态库，源码位于 `app/src/main/cpp/libraw/`，许可证与版权信息见 `NOTICE`。

## 资产

`app/src/main/assets/` 中随仓库分发的二进制共 26 个，均为本项目自产：

- LUT：`lut_alexa`、`lut_p9vibrant`、`lut_arri709`、`lut_arrireveal`、`lut_arridune`、`lut_arrilala`、`lut_arrijoker`，F-胶片家族 `lut_fuji_acros`、`lut_fuji_chrome`、`lut_fuji_cneg`、`lut_fuji_nn`、`lut_fuji_pnh`，以及 K-胶片家族 `lut_kodak_400`、`lut_kodak_200`、`lut_kodak_100`、`lut_kodak_64`
- 风格参数表：`styleA_s0` ~ `styleA_s5`
- 蒸馏/网络模型：`m9_distill.onnx`、`lm9_net.onnx`，以及模型色彩两段网络 `m7_stage1.onnx`、`m7_stage2.onnx`

上述以外的一切二进制（含第三方预训练权重、厂商引擎与参数快照）都不在本仓库内，也不会被接受为贡献。部分未随仓库分发的模型档（如高分辨率 StyleTrans）在缺失时对应处理档位自动降级。

## 许可

本项目代码以 MIT 许可发布，见 `LICENSE`。`NOTICE` 列出内置第三方组件的版权与许可。
