package interview.guide.modules.user.repository;

import interview.guide.modules.user.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户 Repository
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    boolean existsByNickname(String nickname);

    Optional<UserEntity> findByNickname(String nickname);

    List<UserEntity> findAllByOrderByCreatedAtAsc();
}
