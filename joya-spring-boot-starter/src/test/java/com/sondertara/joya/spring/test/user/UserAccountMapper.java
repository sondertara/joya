package com.sondertara.joya.spring.test.user;

import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.support.annotation.Query;

import java.util.List;
import java.util.Optional;

public interface UserAccountMapper extends JoyaMapper<UserAccount> {

    Optional<UserAccount> findByUserName(String userName);

    List<UserAccount> findByAgeGreaterThanEqual(Integer minAge);

    long countByAgeGreaterThan(Integer age);

    @Query("SELECT * FROM t_user_account WHERE user_name = ?1")
    Optional<UserAccount> selectByRawName(String name);
}
