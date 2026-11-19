package com.infinitecanvas.canvas.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型渠道，对应 t_channel。聚合根：一个 OpenAI 兼容端点 + 一把 Key，
 * 下面的模型是它的附属数据（随渠道整体保存，没有独立的增删接口）。
 *
 * <p>这里最容易设计错的是 Key 的存法。三条底线：
 * <ol>
 *   <li>明文永不落库、永不进日志、永不进异常信息；</li>
 *   <li>列表接口不解密——所以另存一列 {@code apiKeyHint} 专门给列表看；</li>
 *   <li>密文带上版本前缀（{@code v1:}），将来换算法或换密钥可以按前缀分批迁移。</li>
 * </ol>
 */
@Data
@TableName("t_channel")
public class Channel {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 业务键，对外就是 channelId。前端模型值是 {@code channelKey::modelName}。 */
    private String channelKey;

    private String name;

    /** 接口根地址。可能是 {@code .../v1}，也可能不带，拼接规则见 ChannelApiUrls。 */
    private String baseUrl;

    /** AES-GCM 密文，形如 {@code v1:base64(iv||ciphertext||tag)}。 */
    private String apiKeyCipher;

    /** 脱敏提示，形如 {@code sk-a****wxyz}，只用于展示。 */
    private String apiKeyHint;

    /** openai / gemini。决定拉模型列表和后续生成调用的路径。 */
    private String apiFormat;

    private Integer enabled;

    private Integer sortIndex;

    private LocalDateTime lastTestAt;

    /** 1=通过 0=失败 null=从未测过。用 Integer 而不是 boolean，是为了表达"没测过"。 */
    private Integer lastTestOk;

    private String lastTestError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
