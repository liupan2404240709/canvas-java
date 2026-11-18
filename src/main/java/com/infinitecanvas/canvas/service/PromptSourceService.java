package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.dto.CreatePromptSourceRequest;
import com.infinitecanvas.canvas.dto.UpdatePromptSourceRequest;
import com.infinitecanvas.canvas.entity.PromptSource;
import com.infinitecanvas.canvas.vo.PromptSourceVO;

import java.util.List;

public interface PromptSourceService {

    /** 列表。首次调用会把 7 个内置源播种到当前用户名下。 */
    List<PromptSourceVO> list(Long userId);

    PromptSourceVO create(Long userId, CreatePromptSourceRequest request);

    PromptSourceVO update(Long userId, String sourceKey, UpdatePromptSourceRequest request);

    /** 逻辑删除源，同时物理删除它的提示词（提示词没有独立生命周期）。 */
    void delete(Long userId, String sourceKey);

    /** 按业务键取自己的源，取不到抛 404。同步服务复用它做归属校验。 */
    PromptSource getOwned(Long userId, String sourceKey);

    /** 启用中的源，按创建顺序。查询提示词时要靠它拿到 source_id 范围。 */
    List<PromptSource> enabled(Long userId);
}
