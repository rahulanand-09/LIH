package com.rupeek.flow.registry.store;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FlowPointerRepository extends MongoRepository<FlowPointerDocument, String> {

    List<FlowPointerDocument> findByTypeOrderByRefAsc(String type);
}
