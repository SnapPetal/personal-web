package biz.thonbecker.personal.foosball.platform.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface PlayerRepository extends CrudRepository<Player, Long> {

    Optional<Player> findByTenantIdAndName(Long tenantId, String name);

    Optional<Player> findByTenantIdAndId(Long tenantId, Long id);

    List<Player> findByTenantIdAndNameContainingIgnoreCase(Long tenantId, String name);

    List<Player> findAllByTenantIdOrderByNameAsc(Long tenantId);

    // Rating/Ranking queries
    List<Player> findAllByTenantIdOrderByRatingDesc(Long tenantId);

    List<Player> findTop10ByTenantIdOrderByRatingDesc(Long tenantId);

    List<Player> findByTenantIdAndGamesPlayedGreaterThanEqualOrderByRatingDesc(Long tenantId, int minGames);

    long countByTenantId(Long tenantId);
}
