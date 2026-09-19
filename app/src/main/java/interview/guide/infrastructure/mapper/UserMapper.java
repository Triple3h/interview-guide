package interview.guide.infrastructure.mapper;

import interview.guide.modules.user.model.AdminUserDTO.AdminUserResponse;
import interview.guide.modules.user.model.UserDTO.UserResponse;
import interview.guide.modules.user.model.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 用户实体到 DTO 的映射器
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface UserMapper {

    UserResponse toResponse(UserEntity entity);

    List<UserResponse> toResponseList(List<UserEntity> entities);

    /**
     * 管理端用户视图：角色中文名由角色枚举派生，不落库
     */
    @Mapping(target = "roleLabel", source = "role.label")
    AdminUserResponse toAdminResponse(UserEntity entity);

    List<AdminUserResponse> toAdminResponseList(List<UserEntity> entities);
}
