package com.infinitecanvas.canvas.infra.channel;

import java.util.List;

/**
 * 国产模型厂商的内置预设。
 *
 * <p>它解决的是"新建渠道要手填一堆东西"的问题：地址、协议、常见模型名，用户只需要粘一把自己的 Key。
 * 这里<b>不预置任何 Key</b>，也不预置"默认渠道"——Key 是用户自己的资产，凭空插一条空 Key 的渠道
 * 只会让列表里多一行永远调不通的配置。
 *
 * <p>模型名与端点已于 2026-11 核对各厂商公开文档。这类信息变化很快（DeepSeek 的
 * {@code deepseek-chat} 就在 2026-07 退役了），所以：
 * <ul>
 *   <li>预设只放<b>能力明确、名字稳定</b>的模型；</li>
 *   <li>模型 ID 带日期后缀的（如火山方舟的文本模型），在 {@code note} 里明确写"以控制台为准"，
 *       而不是塞一个明天就失效的 ID；</li>
 *   <li>渠道建好之后可以用"拉取上游模型"刷新，那才是长期正确的做法——预设只是起步的脚手架。</li>
 * </ul>
 */
public final class ChannelPresets {

    /** 预设模型。 */
    public record PresetModel(String name, String capability) {
    }

    /** 一个预设渠道。{@code note} 给前端显示成一行灰字提示。 */
    public record Preset(String key, String name, String baseUrl, String apiFormat, String homepage,
                         String note, List<PresetModel> models) {
    }

    public static final List<Preset> ALL = List.of(
            new Preset("bailian", "阿里云百炼（通义）",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "openai",
                    "https://bailian.console.aliyun.com",
                    "OpenAI 兼容端点。生图与对话都走这里，语音合成走 /audio/speech。",
                    List.of(
                            new PresetModel("qwen-image-3.0", "image"),
                            new PresetModel("qwen-image-3.0-pro", "image"),
                            new PresetModel("qwen-max", "text"),
                            new PresetModel("qwen-audio-3.0-tts-flash", "audio"))),

            new Preset("ark", "火山方舟（豆包）",
                    "https://ark.cn-beijing.volces.com/api/v3", "openai",
                    "https://console.volcengine.com/ark",
                    "地址末尾是 /api/v3 不需要再加版本段。文本模型 ID 带日期后缀（如 doubao-seed-2-1-pro-260628），请从控制台复制完整 ID。",
                    List.of(
                            new PresetModel("doubao-seedream-5.0", "image"),
                            new PresetModel("doubao-seedance-2.5", "video"))),

            new Preset("zhipu", "智谱 AI（GLM）",
                    "https://open.bigmodel.cn/api/paas/v4", "openai",
                    "https://open.bigmodel.cn",
                    "GLM-Image 在中文海报、图文混排上表现好；CogView 是上一代生图模型。",
                    List.of(
                            new PresetModel("glm-image", "image"),
                            new PresetModel("cogview-4", "image"),
                            new PresetModel("glm-5.3", "text"))),

            new Preset("siliconflow", "硅基流动 SiliconFlow",
                    "https://api.siliconflow.cn/v1", "openai",
                    "https://cloud.siliconflow.cn",
                    "聚合平台，一个 Key 能用多家开源模型，模型 ID 形如 组织/模型名。建议建好后点「拉取上游模型」自己挑。",
                    List.of(
                            new PresetModel("Qwen/Qwen-Image", "image"),
                            new PresetModel("Kwai-Kolors/Kolors", "image"),
                            new PresetModel("FunAudioLLM/CosyVoice2-0.5B", "audio"))),

            new Preset("deepseek", "DeepSeek（深度求索）",
                    // 官方明确说 base_url 不需要 /v1，拼接时由 ChannelApiUrls 自动补
                    "https://api.deepseek.com", "openai",
                    "https://platform.deepseek.com",
                    "纯文本渠道。deepseek-chat / deepseek-reasoner 已于 2026-07 退役，请用 deepseek-flash。",
                    List.of(
                            new PresetModel("deepseek-flash", "text"),
                            new PresetModel("deepseek-v4-pro", "text"))),

            new Preset("ollama", "本地 Ollama（自建）",
                    "http://127.0.0.1:11434/v1", "openai",
                    "https://ollama.com",
                    "不预置模型：先在本机 ollama pull，再点「拉取上游模型」把已有模型加进来。",
                    List.of())
    );

    private ChannelPresets() {
    }
}
