package com.example.tuanjian.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConstraintRequest {

    /** 建模板/改模板时必须给齐条件；带 templateId 的对比请求改为以库里的模板为准。 */
    public interface RequireTemplateFields {
    }

    /**
     * 从模板发起对比时携带。服务端以库里仍启用的模板为准重新加载条件，
     * 防止模板删除后前端/缓存继续拿旧编号和旧条件开绿灯。
     */
    private Long templateId;

    @NotBlank(message = "模板名称不能为空", groups = RequireTemplateFields.class)
    private String templateName;

    @NotNull(message = "预算上限不能为空", groups = RequireTemplateFields.class)
    @Positive(message = "预算上限必须为正数", groups = RequireTemplateFields.class)
    private BigDecimal budgetLimit;

    @NotNull(message = "最大出行天数不能为空", groups = RequireTemplateFields.class)
    @Positive(message = "最大出行天数必须为正数", groups = RequireTemplateFields.class)
    private Integer maxDurationDays;

    @NotNull(message = "参与人数不能为空", groups = RequireTemplateFields.class)
    @Positive(message = "参与人数必须为正数", groups = RequireTemplateFields.class)
    private Integer participantCount;

    private String requiredActivities;

}