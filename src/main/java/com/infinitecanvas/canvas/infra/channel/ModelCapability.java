package com.infinitecanvas.canvas.infra.channel;

import java.util.List;
import java.util.Locale;

/**
 * 模型能力分类。前端的 {@code ModelCapability} 就是这四个值，必须逐字一致。
 *
 * <p>{@link #guess(String)} 是"拉完上游模型列表，顺手给个初始分类"用的 best-effort：
 * 上游的 {@code /models} 只返回一个名字，不告诉你这是生图还是对话。
 * 猜错的成本很低（用户在渠道编辑里改一下），猜对的收益是"拉 100 个模型不用逐个点"。
 *
 * <p>比前端 {@code guessCapability} 多补了国产模型的关键字，因为这里的预设是国产厂商：
 * 前端把 {@code wan} 当视频关键字，会把 {@code wanx-v1}（通义万相 <b>生图</b>）误判成视频，
 * 所以这里改用 {@code wan2}/{@code t2v}/{@code i2v} 这种更精确的后缀。
 */
public enum ModelCapability {

    IMAGE("image"),
    VIDEO("video"),
    TEXT("text"),
    AUDIO("audio");

    /**
     * 判定顺序很重要：先 video、再 audio、最后 image。
     * 因为 {@code qwen-audio-3.0-tts} 这种名字里同时有"音频"和"tts"，
     * 而 {@code seedance}（视频）不带 image 字样；顺序反了会互相抢。
     */
    private static final List<String> VIDEO_KEYS = List.of(
            "video", "sora", "veo", "kling", "seedance", "hailuo", "t2v", "i2v", "vidu");
    private static final List<String> AUDIO_KEYS = List.of(
            "audio", "tts", "speech", "voice", "music", "sound", "cosyvoice", "sambert");
    private static final List<String> IMAGE_KEYS = List.of(
            "image", "seedream", "dall-e", "dalle", "imagen", "flux", "sdxl",
            "stable-diffusion", "midjourney", "kolors", "cogview", "wanx", "wan2", "hunyuan-image");

    private final String code;

    ModelCapability(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 未知值一律归到 text：宁可让用户从"对话"改成"生图"，也不要凭空造一个非法值。 */
    public static ModelCapability guess(String modelName) {
        String name = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        if (VIDEO_KEYS.stream().anyMatch(name::contains)) {
            return VIDEO;
        }
        if (AUDIO_KEYS.stream().anyMatch(name::contains)) {
            return AUDIO;
        }
        if (IMAGE_KEYS.stream().anyMatch(name::contains)) {
            return IMAGE;
        }
        return TEXT;
    }

    /** 校验客户端传上来的能力值。返回 null 表示非法，由调用方决定怎么报错。 */
    public static ModelCapability parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (ModelCapability capability : values()) {
            if (capability.code.equals(value)) {
                return capability;
            }
        }
        return null;
    }
}
