package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.PromptSource;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PromptSourceMapper extends BaseMapper<PromptSource> {

    /**
     * 取出"该同步了"的源，供定时任务使用。
     *
     * <p>到期判断放在 SQL 里而不是 Java 里：源可能有几千条，全查出来在内存里比
     * {@code lastFetchedAt} 会白白拉一遍数据；而且这个条件只依赖单行字段，SQL 表达最直接。
     * {@code refresh_interval_minutes = 0} 表示"只手动刷新"，不参与扫描。
     */
    List<PromptSource> selectDueSources(@Param("limit") int limit);
}
