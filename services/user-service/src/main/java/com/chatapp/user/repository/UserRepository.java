package com.chatapp.user.repository;

import java.util.List;
import java.util.UUID;

import com.chatapp.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds active users by username prefix, display-name text or exact phone.
     * Leaves out the caller and everybody who blocked the caller.
     * LIKE patterns arrive already lower-cased and escaped with '!' (see ProfileService).
     */
    @Query("""
            select new com.chatapp.user.repository.UserWithProfile(u, p)
            from User u, UserProfile p
            where p.userId = u.id
              and u.id <> :me
              and u.accountStatus = com.chatapp.user.enums.AccountStatus.ACTIVE
              and not exists (select 1 from BlockedUser b where b.blockerId = u.id and b.blockedId = :me)
              and (lower(u.username) like :prefix escape '!'
                   or lower(p.displayName) like :contains escape '!'
                   or u.phone = :phone)
            order by u.username
            """)
    List<UserWithProfile> search(@Param("me") UUID me,
                                 @Param("prefix") String prefix,
                                 @Param("contains") String contains,
                                 @Param("phone") String phone,
                                 Pageable pageable);
}
