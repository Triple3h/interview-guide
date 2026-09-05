package interview.guide.infrastructure.mapper;

import interview.guide.modules.learning.model.LearningPlanDTO.PlanItemResponse;
import interview.guide.modules.learning.model.LearningPlanItemEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 学习计划条目实体到 DTO 的映射器
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LearningPlanItemMapper {

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "statusLabel", source = "status.label")
    PlanItemResponse toResponse(LearningPlanItemEntity entity);

    List<PlanItemResponse> toResponseList(List<LearningPlanItemEntity> entities);
}
