package com.rupeek.flow.registry.store;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface FlowVersionRepository extends MongoRepository<FlowVersionDocument, String> {

    Optional<FlowVersionDocument> findByRefAndVersion(String ref, int version);
}
