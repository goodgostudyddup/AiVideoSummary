package com.example.aispringvideo.service.impl;

import com.example.aispringvideo.entity.VideoTask;
import com.example.aispringvideo.mapper.VideoTaskMapper;
import com.example.aispringvideo.service.IVideoTaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 视频处理任务表 服务实现类
 * </p>
 *
 * @author yxc
 * @since 2026-06-04
 */
@Service
public class VideoTaskServiceImpl extends ServiceImpl<VideoTaskMapper, VideoTask> implements IVideoTaskService {

}
