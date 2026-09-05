package interview.guide.infrastructure.mapper;

import interview.guide.modules.learning.model.LearningRecordDTO.LearningRecordResponse;
import interview.guide.modules.learning.model.LearningRecordEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 学习记录实体到 DTO 的映射器
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LearningRecordMapper {

    @Mapping(target = "mastery", expression = "java(entity.getMastery().name())")
    @Mapping(target = "masteryLabel", source = "mastery.label")
    LearningRecordResponse toResponse(LearningRecordEntity entity);

    List<LearningRecordResponse> toResponseList(List<LearningRecordEntity> entities);
}
