package com.sondertara.joya.spring.test.user;

import com.sondertara.joya.core.mapper.JoyaMapper;

import java.util.List;
import java.util.Optional;

public interface UserAccountMapper extends JoyaMapper<UserAccount> {

    default Optional<UserAccount> findByUserName(String userName) {
        return query().where(w -> w.eq(UserAccount::getUserName, userName)).one();
    }

    default List<UserAccount> findByAgeGreaterThanEqual(Integer minAge) {
        return query().where(w -> w.gte(UserAccount::getAge, minAge)).list();
    }

    default long countByAgeGreaterThan(Integer age) {
        return query().where(w -> w.gt(UserAccount::getAge, age)).count();
    }

    default Optional<UserAccount> selectByRawName(String name) {
        return sql("SELECT * FROM t_user_account WHERE user_name = ?", name).one(UserAccount.class);
    }
}
