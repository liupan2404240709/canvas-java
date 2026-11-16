package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.Asset;
import com.infinitecanvas.canvas.vo.TagCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    /**
     * 标签聚合，给素材页的"按标签筛选"下拉用。
     *
     * <p>用 MySQL 8 的 {@code JSON_TABLE} 把 JSON 数组炸成行再 group by，
     * 比"把 tags_json 全查出来在 Java 里统计"少一次全表传输，
     * 也比另建一张 t_asset_tag 关联表少一张表和一套同步逻辑。
     */
    List<TagCountVO> selectTagCounts(@Param("userId") Long userId, @Param("limit") int limit);
}
