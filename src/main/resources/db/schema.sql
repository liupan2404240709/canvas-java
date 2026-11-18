-- 阶段 1：账号体系表结构
-- 说明：
-- 1. 全部使用 IF NOT EXISTS，应用每次启动都能安全执行；
-- 2. 字符集 utf8mb4，昵称里允许 emoji；
-- 3. 时间列交给 MySQL 默认值兜底，避免演示脚本插入数据时漏字段。

CREATE TABLE IF NOT EXISTS t_user
(
    id            BIGINT       NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID 生成',
    username      VARCHAR(32)  NOT NULL COMMENT '登录名，全局唯一',
    email         VARCHAR(128) DEFAULT NULL COMMENT '邮箱，可空；唯一索引允许多个 NULL',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，固定 60 位，留余量给算法升级',
    nickname      VARCHAR(32)  DEFAULT NULL COMMENT '展示昵称',
    avatar_url    VARCHAR(512) DEFAULT NULL COMMENT '头像地址',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    last_login_at DATETIME     DEFAULT NULL COMMENT '最近一次登录成功时间',
    last_login_ip VARCHAR(45)  DEFAULT NULL COMMENT '最近一次登录 IP，兼容 IPv6 长度',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删 1=已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户表';

-- 阶段 2：画布项目
-- 设计要点：
-- 1. 项目是主表，节点和连线是附属表，靠 project_id 关联；
-- 2. 节点/连线用前端给的 id 作为业务键（node_key / edge_key），数据库主键仍是自增雪花，
--    这样前端不需要维护两套 id，也不怕以后换 id 生成策略；
-- 3. node_count / connection_count 是冗余字段，列表页显示"N 个节点"时不用 count(*)；
-- 4. version 给乐观锁用，每次保存快照 +1。
CREATE TABLE IF NOT EXISTS t_canvas_project
(
    id               BIGINT       NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID',
    user_id          BIGINT       NOT NULL COMMENT '归属用户',
    title            VARCHAR(100) NOT NULL COMMENT '画布标题',
    background_mode  VARCHAR(32)  NOT NULL DEFAULT 'lines' COMMENT '背景样式：lines / dots / blank',
    show_image_info  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否在图片节点上显示尺寸等信息',
    viewport_x       DOUBLE       NOT NULL DEFAULT 0 COMMENT '画布视口平移 X',
    viewport_y       DOUBLE       NOT NULL DEFAULT 0 COMMENT '画布视口平移 Y',
    viewport_k       DOUBLE       NOT NULL DEFAULT 1 COMMENT '画布缩放比例',
    node_count       INT          NOT NULL DEFAULT 0 COMMENT '节点数，保存快照时更新',
    connection_count INT          NOT NULL DEFAULT 0 COMMENT '连线数，保存快照时更新',
    version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次保存 +1',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删 1=已删',
    PRIMARY KEY (id),
    KEY idx_project_user_updated (user_id, deleted, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='画布项目表';

CREATE TABLE IF NOT EXISTS t_canvas_node
(
    id         BIGINT       NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID',
    project_id BIGINT       NOT NULL COMMENT '所属画布',
    node_key   VARCHAR(64)  NOT NULL COMMENT '前端节点 id，项目内唯一',
    type       VARCHAR(64)  NOT NULL COMMENT '节点类型：image/text/config/video/audio/group 或插件类型',
    title      VARCHAR(200) DEFAULT NULL COMMENT '节点标题',
    pos_x      DOUBLE       NOT NULL DEFAULT 0 COMMENT '画布坐标 X',
    pos_y      DOUBLE       NOT NULL DEFAULT 0 COMMENT '画布坐标 Y',
    width      DOUBLE       NOT NULL DEFAULT 0 COMMENT '节点宽度',
    height     DOUBLE       NOT NULL DEFAULT 0 COMMENT '节点高度',
    metadata   JSON         DEFAULT NULL COMMENT '节点扩展数据，结构随节点类型变化，原样存取',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_node_project_key (project_id, node_key),
    KEY idx_node_project (project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='画布节点表';

CREATE TABLE IF NOT EXISTS t_canvas_connection
(
    id            BIGINT      NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID',
    project_id    BIGINT      NOT NULL COMMENT '所属画布',
    edge_key      VARCHAR(64) NOT NULL COMMENT '前端连线 id，项目内唯一',
    from_node_key VARCHAR(64) NOT NULL COMMENT '起始节点 node_key',
    to_node_key   VARCHAR(64) NOT NULL COMMENT '目标节点 node_key',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_edge_project_key (project_id, edge_key),
    KEY idx_edge_project (project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='画布连线表';

-- 阶段 3：文件与素材库
-- 设计要点：
-- 1. t_file 不是"文件本身"，而是"某个用户对某份内容的一条引用"。同一份内容（sha256 相同）
--    在磁盘上只存一份，但每个上传过它的用户都有自己的记录，才能各自删除、各自统计用量；
-- 2. 不存 url，只存 storage_key。url 由服务端拼（含域名、CDN、签名参数），
--    一旦把 url 落库，换域名或者迁对象存储就得全表刷一遍；
-- 3. width / height 在上传时用 ImageReader 只读文件头拿到，素材和画布节点都直接用，
--    不用每次渲染再解析一遍图片；
-- 4. 没有 deleted：文件是技术资源，没有"回收站"语义，删就是真删，
--    磁盘文件靠 t_asset 的引用情况决定要不要一起删（见 FileServiceImpl#delete）。
CREATE TABLE IF NOT EXISTS t_file
(
    id            BIGINT       NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID',
    user_id       BIGINT       NOT NULL COMMENT '归属用户',
    sha256        CHAR(64)     NOT NULL COMMENT '内容摘要，秒传与去重的唯一依据',
    storage_key   VARCHAR(160) NOT NULL COMMENT '存储相对路径，形如 a3/a3f9...，不含域名',
    original_name VARCHAR(255) DEFAULT NULL COMMENT '上传时的文件名，下载时还原',
    mime_type     VARCHAR(128) NOT NULL COMMENT '服务端判定的 MIME，不采信客户端声明',
    extension     VARCHAR(16)  DEFAULT NULL COMMENT '扩展名，拼下载文件名用',
    bytes         BIGINT       NOT NULL COMMENT '字节数',
    width         INT          DEFAULT NULL COMMENT '图片宽，非图片为空',
    height        INT          DEFAULT NULL COMMENT '图片高，非图片为空',
    biz_type      VARCHAR(32)  NOT NULL DEFAULT 'asset' COMMENT '业务来源：asset / canvas / avatar',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    -- (user_id, sha256) 而不是 sha256 单独唯一：秒传是"同一个用户重复上传"，
    -- 跨用户共享的是磁盘文件，记录仍然各存一份
    UNIQUE KEY uk_file_user_sha (user_id, sha256),
    KEY idx_file_sha (sha256)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='文件表';

CREATE TABLE IF NOT EXISTS t_asset
(
    id            BIGINT        NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID',
    user_id       BIGINT        NOT NULL COMMENT '归属用户',
    kind          VARCHAR(16)   NOT NULL COMMENT '素材类型：text / image / video / audio',
    title         VARCHAR(200)  NOT NULL COMMENT '标题',
    cover_url     VARCHAR(1024) DEFAULT NULL COMMENT '封面地址；图片素材没单独传封面时由服务端填文件地址',
    source        VARCHAR(100)  DEFAULT NULL COMMENT '来源：手动 / AI 生成 / 网址',
    note          VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    content       MEDIUMTEXT    DEFAULT NULL COMMENT '文本素材正文，只有 kind=text 有值',
    file_id       BIGINT        DEFAULT NULL COMMENT '关联 t_file.id，文本素材为空',
    storage_key   VARCHAR(160)  DEFAULT NULL COMMENT '冗余自 t_file，列表页不 join 就能拼出访问地址',
    width         INT           DEFAULT NULL COMMENT '宽（图片/视频）',
    height        INT           DEFAULT NULL COMMENT '高（图片/视频）',
    bytes         BIGINT        DEFAULT NULL COMMENT '文件字节数，冗余自 t_file，列表页省一次关联',
    mime_type     VARCHAR(128)  DEFAULT NULL COMMENT 'MIME，冗余自 t_file',
    duration_ms   INT           DEFAULT NULL COMMENT '音视频时长，前端算好传上来',
    tags_json     JSON          DEFAULT NULL COMMENT '标签数组，如 ["人像","暖色"]，用 JSON_CONTAINS 过滤',
    metadata_json JSON          DEFAULT NULL COMMENT '扩展字段，原样透传，前端已有 metadata 约定',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删 1=已删',
    PRIMARY KEY (id),
    KEY idx_asset_user_created (user_id, deleted, created_at),
    KEY idx_asset_user_kind (user_id, deleted, kind),
    KEY idx_asset_file (file_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='素材表';

-- 阶段 4：提示词库
-- 设计要点：
-- 1. 源和提示词是"父表 + 附属数据"的关系，提示词没有独立接口，只能通过刷新源写入；
-- 2. 源用 source_key 做业务键（内置源直接用 registry 里的 id，自定义源生成 src_xxx），
--    和 t_canvas_node.node_key 是同一个思路：数据库主键每次重建都可能变，对外只认业务键；
-- 3. t_prompt 没有 deleted：它没有"单条删除"的业务语义，整源同步时差量删除就够了；
--    源被删除时提示词随源一起物理删除（在同一个事务里）；
-- 4. content_sha256 / etag / last_modified 是"幂等同步"的依据：
--    内容没变就不写库，避免每 30 分钟把几千行删了重插一遍。
CREATE TABLE IF NOT EXISTS t_prompt_source
(
    id                       BIGINT        NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID',
    user_id                  BIGINT        NOT NULL COMMENT '归属用户',
    source_key               VARCHAR(64)   NOT NULL COMMENT '业务键，对外就是 sourceId；内置源用 registry id',
    name                     VARCHAR(100)  NOT NULL COMMENT '源名称，同时作为提示词的 category',
    url                      VARCHAR(1024) NOT NULL COMMENT 'JSON 地址（http/https）',
    homepage                 VARCHAR(1024) DEFAULT NULL COMMENT '主页，用于 githubUrl 兜底',
    enabled                  TINYINT       NOT NULL DEFAULT 1 COMMENT '0=停用，停用后不参与同步也不出现在列表',
    built_in                 TINYINT       NOT NULL DEFAULT 0 COMMENT '1=内置源，不可删除、不可改地址',
    refresh_interval_minutes INT           NOT NULL DEFAULT 30 COMMENT '刷新间隔（分钟），0=只手动刷新',
    prompt_count             INT           NOT NULL DEFAULT 0 COMMENT '上次同步后的条数',
    content_sha256           CHAR(64)      DEFAULT NULL COMMENT '上次成功同步的内容摘要，相同则跳过写库',
    etag                     VARCHAR(128)  DEFAULT NULL COMMENT 'HTTP ETag，用于 If-None-Match 条件请求',
    last_modified            VARCHAR(64)   DEFAULT NULL COMMENT 'HTTP Last-Modified 原文，用于 If-Modified-Since',
    last_fetched_at          DATETIME      DEFAULT NULL COMMENT '最近一次尝试同步时间（成功失败都写）',
    last_success_at          DATETIME      DEFAULT NULL COMMENT '最近一次成功同步时间',
    last_error               VARCHAR(500)  DEFAULT NULL COMMENT '最近一次失败原因，成功后清空',
    created_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                  TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删 1=已删',
    PRIMARY KEY (id),
    -- 同一用户下 source_key 唯一：内置源播种时靠它做幂等的 INSERT 判定
    UNIQUE KEY uk_prompt_source_user_key (user_id, source_key),
    -- 调度扫描："到期的、启用的源"。deleted 放中间是因为它是等值条件，
    -- last_fetched_at 是范围条件，必须排在后面
    KEY idx_prompt_source_due (enabled, deleted, last_fetched_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='提示词源表';

CREATE TABLE IF NOT EXISTS t_prompt
(
    id                    BIGINT        NOT NULL COMMENT '主键，MyBatis-Plus 雪花 ID',
    user_id               BIGINT        NOT NULL COMMENT '归属用户，冗余自源，列表查询不用 join',
    source_id             BIGINT        NOT NULL COMMENT '所属源 t_prompt_source.id',
    prompt_key            VARCHAR(128)  NOT NULL COMMENT '源内 id，对外就是 Prompt.id',
    title                 VARCHAR(300)  NOT NULL COMMENT '标题',
    prompt                MEDIUMTEXT    NOT NULL COMMENT '提示词正文',
    description           VARCHAR(1000) DEFAULT NULL COMMENT '说明',
    cover_url             VARCHAR(1024) DEFAULT NULL COMMENT '封面，缺失时取第一张参考图',
    reference_image_urls  JSON          DEFAULT NULL COMMENT '参考图数组',
    tags_json             JSON          DEFAULT NULL COMMENT '标签数组，JSON_CONTAINS 过滤',
    preview               VARCHAR(2000) DEFAULT NULL COMMENT '预览文本（部分源提供）',
    author                VARCHAR(200)  DEFAULT NULL COMMENT '作者',
    source_url            VARCHAR(1024) DEFAULT NULL COMMENT '该条的原始链接',
    image_mode            VARCHAR(64)   DEFAULT NULL COMMENT '推荐图片模式',
    image_model           VARCHAR(128)  DEFAULT NULL COMMENT '推荐模型',
    image_size            VARCHAR(64)   DEFAULT NULL COMMENT '推荐尺寸',
    image_count           INT           DEFAULT NULL COMMENT '推荐张数',
    sort_index            INT           NOT NULL DEFAULT 0 COMMENT '在源 JSON 里的下标，用于保持源的策展顺序',
    remote_created_at     DATETIME      DEFAULT NULL COMMENT '源里的 createdAt，解析失败为空',
    remote_updated_at     DATETIME      DEFAULT NULL COMMENT '源里的 updatedAt，解析失败为空',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入库时间，重复同步不变',
    PRIMARY KEY (id),
    -- 幂等同步的关键：同一源内 prompt_key 唯一，重复同步走 ON DUPLICATE KEY UPDATE
    UNIQUE KEY uk_prompt_source_key (source_id, prompt_key),
    -- 列表查询：按源分组 + 源内顺序；source_id 是等值/范围条件，sort_index 提供排序
    KEY idx_prompt_user_source_order (user_id, source_id, sort_index)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='提示词表';
