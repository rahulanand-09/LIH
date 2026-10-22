package com.rupeek.flow.registry.store;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface TaskVersionRepository extends MongoRepository<TaskVersionDocument, String> {

    Optional<TaskVersionDocument> findByRefAndVersion(String ref, int version);
}
