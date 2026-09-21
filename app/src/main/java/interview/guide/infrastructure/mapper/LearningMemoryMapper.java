package interview.guide.infrastructure.mapper;

import interview.guide.modules.learning.model.LearningMemoryDTO.LearningMemoryResponse;
import interview.guide.modules.learning.model.LearningMemoryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 个人记忆实体到 DTO 的映射器
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface LearningMemoryMapper {

    @Mapping(target = "kind", expression = "java(entity.getKind().name())")
    @Mapping(target = "kindLabel", source = "kind.label")
    LearningMemoryResponse toResponse(LearningMemoryEntity entity);

    List<LearningMemoryResponse> toResponseList(List<LearningMemoryEntity> entities);
}
