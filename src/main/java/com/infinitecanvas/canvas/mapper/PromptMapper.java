package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.Prompt;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

public interface PromptMapper extends BaseMapper<Prompt> {

    /** 批量 upsert：靠 uk_prompt_source_key 判重，存在则更新内容，不存在则插入。 */
    int upsertBatch(@Param("items") List<Prompt> items);

    /**
     * 删掉源里已经不存在的提示词。
     *
     * <p>{@code keys} 为空表示上游这次返回了 0 条（内置源会先被拦掉，不会走这里），
     * 此时清空该源的全部提示词——语义是"上游把整份列表下了"，不是"这次没取到"。
     */
    int deleteNotInKeys(@Param("sourceId") Long sourceId, @Param("keys") Collection<String> keys);

    /** 标签聚合（忽略标签筛选，只带关键字和源范围），用于左侧标签云。 */
    List<String> selectTagFacet(@Param("userId") Long userId,
                                @Param("sourceIds") Collection<Long> sourceIds,
                                @Param("keyword") String keyword,
                                @Param("limit") int limit);
}
