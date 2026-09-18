package com.example.tuanjian.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 从当次对比结果落批次的请求。
 * 模板编号必须仍在启用模板列表中；天数、必备活动等条件由服务端按该模板重新读取，
 * 不接收页面上可能已经过期的条件副本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupBatchLandingRequest {

    @NotNull(message = "方案不能为空")
    private Long planId;

    @NotNull(message = "约束模板编号不能为空")
    @JsonAlias("id")
    private Long templateId;

    @NotNull(message = "出行日期不能为空")
    private LocalDate travelDate;

    @NotNull(message = "成团人数不能为空")
    @Positive(message = "成团人数必须为正数")
    private Integer groupSize;

}
