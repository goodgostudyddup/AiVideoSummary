package com.example.aispringvideo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 视频处理任务表
 * </p>
 *
 * @author yxc
 * @since 2026-06-04
 */
@Getter
@Setter
@ToString
@TableName("video_task")
public class VideoTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 任务状态：PENDING, EXTRACTING, TRANSCRIBING, SUMMARIZING, SUCCESS, FAILED
     */
    @TableField("status")
    private String status;

    /**
     * 原始文件名
     */
    @TableField("original_filename")
    private String originalFilename;

    /**
     * 原始视频文件存储路径
     */
    @TableField("file_path")
    private String filePath;

    /**
     * 提取的音频文件路径
     */
    @TableField("audio_path")
    private String audioPath;

    /**
     * 语音转文字结果
     */
    @TableField("transcript")
    private String transcript;

    /**
     * AI摘要结果
     */
    @TableField("summary")
    private String summary;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 处理进度百分比
     */
    @TableField("progress")
    private Integer progress;

    /**
     * 总用时（秒）
     */
    @TableField("duration")
    private Long duration;

    /**
     * 文件 MD5，用于去重
     */
    @TableField("md5")
    private String md5;

    /**
     * 来源 URL（通过 URL 提交时记录）
     */
    @TableField("source_url")
    private String sourceUrl;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
