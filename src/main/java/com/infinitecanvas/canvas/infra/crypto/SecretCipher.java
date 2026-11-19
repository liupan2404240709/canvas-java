package com.infinitecanvas.canvas.infra.crypto;

import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 渠道 API Key 的对称加密。用 AES-256-GCM。
 *
 * <p>为什么是 GCM 而不是 CBC：
 * <ul>
 *   <li>CBC 只保证机密性，密文被篡改解出来是乱码，程序无从察觉——攻击者能把
 *       "某个字段"翻转成另一个值而不被发现；</li>
 *   <li>GCM 是 AEAD，自带 128 位认证标签，密文被改一个字节就会在解密时抛
 *       {@code AEADBadTagException}，属于"要么完整还原、要么明确失败"。</li>
 * </ul>
 *
 * <p>为什么每次加密都换 IV：GCM 对同一密钥复用了 IV 是<b>灾难性</b>的——
 * 两条密文异或就能消掉密钥流，明文关系直接泄露。所以 IV 必须随机生成并随密文一起存，
 * 12 字节（96 位）是 GCM 推荐的默认长度（性能最好，不需要额外压缩步骤）。
 *
 * <p>密钥管理：配置里给的是人写的字符串（{@code app.crypto.secret}），
 * 用 SHA-256 派生固定 32 字节的 AES key。这样做的好处是不用逼着用户去生成 base64 密钥，
 * 代价是这个字符串本身的熵必须够——所以启动时校验长度，太短直接拒绝启动。
 *
 * <p><b>面试点</b>：真正生产环境不应该把密钥放配置文件，而应该走 KMS 或者环境变量 +
 * 密钥轮转（密文前缀 {@code v1:} 就是为轮转留的：换密钥后旧前缀的密文用旧密钥解，
 * 后台任务逐步重加密成 {@code v2:}）。
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** GCM 推荐的 IV 长度。12 字节 = 96 位，是 GCM 的原生长度。 */
    private static final int IV_BYTES = 12;
    /** 认证标签位数。128 位是上限，"改一个字节就发现"靠的就是它。 */
    private static final int TAG_BITS = 128;
    private static final String PREFIX = "v1:";
    /** 明文短于这个长度就整个打码，否则"前 4 后 4"等于泄露了全部。 */
    private static final int MASK_SAFE_LEN = 8;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec keySpec;

    public SecretCipher(@Value("${app.crypto.secret}") String secret) {
        if (secret == null || secret.length() < 16) {
            // 宁可在启动时炸掉，也不要在线上用一把弱密钥加密一堆用户 Key
            throw new IllegalStateException("app.crypto.secret 长度必须 >= 16，当前值为空或过短");
        }
        this.keySpec = new SecretKeySpec(sha256(secret), "AES");
        log.info("渠道密钥加密器已就绪（AES-256-GCM，密钥由配置派生）");
    }

    /**
     * 加密。输出 {@code v1:<base64(iv || ciphertext || tag)>}。
     * 前缀不是装饰：将来换算法时靠它区分新旧密文，否则只能全表重来。
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            throw new BizException(ResultCode.CHANNEL_API_KEY_REQUIRED);
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(sealed, 0, packed, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            // 加密失败只可能是环境问题（JCE 被限制等），不需要用户操作，包装成 500
            throw new IllegalStateException("加密 API Key 失败", e);
        }
    }

    /**
     * 解密。密文被篡改、密钥被换过、格式不对，都统一抛 {@link ResultCode#SECRET_DECRYPT_FAILED}——
     * 对用户来说这三种情况的处理方式完全一样：重新填一次 Key。
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new BizException(ResultCode.SECRET_DECRYPT_FAILED);
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (packed.length <= IV_BYTES + TAG_BITS / 8) {
                throw new BizException(ResultCode.SECRET_DECRYPT_FAILED);
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            byte[] sealed = new byte[packed.length - IV_BYTES];
            System.arraycopy(packed, IV_BYTES, sealed, 0, sealed.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // AEADBadTagException 就在这里：密钥换了或密文被改
            log.warn("API Key 解密失败：{}", e.getClass().getSimpleName());
            throw new BizException(ResultCode.SECRET_DECRYPT_FAILED);
        }
    }

    /**
     * 脱敏提示，形如 {@code sk-a****wxyz}。
     *
     * <p>它单独存一列，是为了让"列渠道列表"这个最高频的接口<b>一行密文都不用解</b>。
     * 解密是要拿密钥的、可能失败、还可能被日志带出去；列表页只需要"能认出来是哪把 Key"。
     */
    public String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        int len = plain.length();
        if (len <= MASK_SAFE_LEN) {
            return "****";
        }
        return plain.substring(0, 4) + "****" + plain.substring(len - 4);
    }

    private static byte[] sha256(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
