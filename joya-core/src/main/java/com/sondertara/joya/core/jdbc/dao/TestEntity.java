package com.sondertara.joya.core.jdbc.dao;

import lombok.Data;

import javax.persistence.Table;
import java.io.Serializable;

@Data
@Table(name = "test_class")
public class TestEntity implements Serializable {
    private String b4y;
    private String id1;
    private String subSystem;
    private String colAndBuy;
}
