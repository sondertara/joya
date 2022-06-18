package com.sondertara.joya.init;

import com.sondertara.joya.jpa.repository.BaseRepository;
import com.sondertara.joya.jpa.repository.BaseRepositoryImpl;
import org.springframework.data.jpa.provider.PersistenceProvider;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryImplementation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.lang.NonNull;

import javax.persistence.EntityManager;
import java.io.Serializable;

/**
 * @author huangxiaohu
 */
public class BaseRepositoryFactory<T, ID extends Serializable> extends JpaRepositoryFactory {

  private EntityManager entityManager;
  private PersistenceProvider extractor;

  public BaseRepositoryFactory(EntityManager entityManager) {
    super(entityManager);
    this.entityManager = entityManager;
    this.extractor = PersistenceProvider.fromEntityManager(entityManager);
  }

  @SuppressWarnings("unchecked")
  @Override
  @NonNull
  protected JpaRepositoryImplementation<?, ?> getTargetRepository(
      RepositoryInformation information, @NonNull EntityManager entityManager) {
    Class<?> repositoryInterface = information.getRepositoryInterface();

    if (isBaseRepository(repositoryInterface)) {
      JpaEntityInformation<T, ID> entityInformation =
          getEntityInformation((Class<T>) information.getDomainType());

      return new BaseRepositoryImpl<>(entityInformation, entityManager);
    }
    return super.getTargetRepository(information, entityManager);
  }

  @Override
  @NonNull
  protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
    if (isBaseRepository(metadata.getRepositoryInterface())) {
      return BaseRepositoryImpl.class;
    }
    return super.getRepositoryBaseClass(metadata);
  }

  private boolean isBaseRepository(Class<?> repositoryInterface) {
    return BaseRepository.class.isAssignableFrom(repositoryInterface);
  }
}
