package de.augmentia.qoc.infratest;

import java.util.List;
import java.util.Optional;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Persistence access for {@link InfratestEntry}.
 *
 * <p>Mutating operations own their transaction so the REST layer stays free
 * of transaction bookkeeping.</p>
 */
@ApplicationScoped
public class InfratestRepository implements PanacheRepository<InfratestEntry> {

    /** All entries, ascending by primary key. */
    public List<InfratestEntry> listOrderedById() {
        return listAll(Sort.ascending("id"));
    }

    @Transactional
    public InfratestEntry create(String name, String content) {
        InfratestEntry entry = new InfratestEntry(name, content);
        persist(entry);
        return entry;
    }

    @Transactional
    public Optional<InfratestEntry> update(long id, String name, String content) {
        InfratestEntry entry = findById(Long.valueOf(id));
        if (entry == null) {
            return Optional.empty();
        }
        entry.setName(name);
        entry.setContent(content);
        return Optional.of(entry);
    }

    @Transactional
    public boolean delete(long id) {
        return deleteById(Long.valueOf(id));
    }
}
