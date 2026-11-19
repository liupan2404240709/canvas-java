package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.dto.ChannelProbeRequest;
import com.infinitecanvas.canvas.dto.CreateChannelRequest;
import com.infinitecanvas.canvas.dto.UpdateChannelRequest;
import com.infinitecanvas.canvas.vo.ChannelProbeVO;
import com.infinitecanvas.canvas.vo.ChannelPresetVO;
import com.infinitecanvas.canvas.vo.ChannelVO;
import com.infinitecanvas.canvas.vo.ModelOptionVO;

import java.util.List;

/**
 * 渠道与模型配置。
 *
 * <p>所有方法第一个参数都是 {@code userId}，且必须来自 token（{@code SecurityUtils.currentUserId()}），
 * 不允许从请求体里取——这和画布、素材的约定一致：归属字段只信凭证。
 */
public interface ChannelService {

    /** 渠道列表，含各自模型。一次查完所有模型再分组，不做 N+1。 */
    List<ChannelVO> list(Long userId);

    ChannelVO get(Long userId, String channelKey);

    ChannelVO create(Long userId, CreateChannelRequest request);

    ChannelVO update(Long userId, String channelKey, UpdateChannelRequest request);

    void delete(Long userId, String channelKey);

    /** 快捷启停：前端一个开关不该触发"传整份配置"。 */
    ChannelVO setEnabled(Long userId, String channelKey, boolean enabled);

    /** 探测一组（可能是还没保存的）配置，不落库。 */
    ChannelProbeVO probe(Long userId, ChannelProbeRequest request);

    /** 测试已保存的渠道，并把结果写进 {@code last_test_*}。 */
    ChannelProbeVO test(Long userId, String channelKey);

    /** 跨渠道的可用模型，可选按能力过滤。 */
    List<ModelOptionVO> models(Long userId, String capability);

    /** 国产厂商预设，纯静态数据。 */
    List<ChannelPresetVO> presets();
}
