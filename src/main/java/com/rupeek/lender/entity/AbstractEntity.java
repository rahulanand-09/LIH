package com.rupeek.lender.entity;

import java.io.Serializable;
import java.util.Date;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Field;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
public abstract class AbstractEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    //    @Field("id")
    @Id
    protected String id;

    @JsonIgnore
    @Version
    protected int version;

    @JsonIgnore
    @CreatedBy
    @Field("created_by")
    protected String createdBy;

    @JsonIgnore
    @CreatedDate
    @Field("creation_date")
    protected Date createdOn;

    @JsonIgnore
    @LastModifiedBy
    @Field("last_updated_by")
    protected String lastUpdatedBy;

    @JsonIgnore
    @LastModifiedDate
    @Field("last_updated")
    protected Date lastUpdatedOn;

    @JsonIgnore
    @CreatedDate
    @Field("createdAt")
    protected Date createdAt;

}
