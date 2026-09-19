package interview.guide.modules.user.repository;

import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户 Repository
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long>,
    JpaSpecificationExecutor<UserEntity> {

    boolean existsByNickname(String nickname);

    Optional<UserEntity> findByNickname(String nickname);

    boolean existsByUsername(String username);

    Optional<UserEntity> findByUsername(String username);

    List<UserEntity> findAllByOrderByCreatedAtAsc();

    long countByRole(UserRole role);
}
