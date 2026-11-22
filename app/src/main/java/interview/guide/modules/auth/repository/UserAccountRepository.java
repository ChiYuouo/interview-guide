package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserAccountEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, Long> {
  Optional<UserAccountEntity> findByUsername(String username);
}
