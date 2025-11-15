

/*
core entities
we will have RateLimiting strategy
here we will create contract (Interface) which will be implemented by diff rateLimiting strategy

<<interface>>
RateLimitingStrategy
 bool isAllowed()

 TokenBucketStrategy implements Ratem
 fixedWindowStrategy implemented RateLimitingStrategy

 data
 TokenBucket {
   - BucketCapacity
   - tokenPerSecond
   - lastRefilledTokenTimeStamp
   + TokenBucket(bucketSize, tokenPerSec)
   + refill(timeStamp){
 }

fixedWindStrategy {
 map<userId, userReqInfo>


}

useReqInfo {
-windowLimit
-currLimit;
-lastTimeRefilled;



}

 */

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface RateLimitingStrategy {
    Boolean isAllowed(User user);
}

class TokenBucket {
    private String userId;
    private final long capacity;
    private long currToken;
    private long lastRefillTimeStamp;
    private long refillRatePerSecond;
    private final Lock lock = new ReentrantLock();

    TokenBucket(String userId, long capacity, long refillRatePerSecond) {
        this.userId = userId;
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.lastRefillTimeStamp = System.currentTimeMillis();
        this.currToken = capacity;
    }

    Boolean isRequestAllowed(User user, long currTimeStamp) {
        lock.lock();
        try {
            long timePastInSec = (currTimeStamp - lastRefillTimeStamp) / 1000;
            currToken = Math.min(capacity, currToken + timePastInSec * refillRatePerSecond);
            if(timePastInSec > 0) {
                lastRefillTimeStamp = currTimeStamp;
            }
            if (currToken >= 0) {
                currToken--;
                return true;
            } else
                return false;
        } finally {
            lock.unlock();
        }
    }
}

class User {
    private String userId;
    private String name;

    User(String userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    public String getId() {
        return userId;
    }

    public String getName() {
        return name;
    }
}

class TokenBucketStrategy implements RateLimitingStrategy {
    private final long bucketCapacity;
    private final long refillRatePerSecond;
    private final Map<String, TokenBucket> userBuckets;

    public TokenBucketStrategy(int bucketCapacity, long refillRatePerSecond) {
        this.bucketCapacity = bucketCapacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.userBuckets = new ConcurrentHashMap<>();
    }


    @Override
    public Boolean isAllowed(User user) {
        if (!userBuckets.containsKey(user.getId())) {
            userBuckets.put(user.getId(), new TokenBucket(user.getId(), this.bucketCapacity, this.refillRatePerSecond));
        }

        TokenBucket tokenBucket = userBuckets.get(user.getId());
        return tokenBucket.isRequestAllowed(user, System.currentTimeMillis());
    }
}

class UserReqInfo {
    private String userId;
    private long token;
    private final long windowCapacity;
    private final long windowInterval;
    private long lastRefillTimeStamp;
    private final Lock mtx = new ReentrantLock();

    UserReqInfo(String userId, long windowCapacity, long windowInterval) {
        this.userId = userId;
        this.windowCapacity = windowCapacity;
        this.windowInterval = windowInterval;
        this.token = windowCapacity;
        this.lastRefillTimeStamp = System.currentTimeMillis();
    }

    Boolean isRequestAllowed(User user, long currTimeStamp) {
        mtx.lock();
        try {
            long timePastInSec = (currTimeStamp - lastRefillTimeStamp) / 1000;
            if (timePastInSec >= windowInterval) {
                token = windowCapacity;
                lastRefillTimeStamp = currTimeStamp;
            }

            if (token > 0) {
                token--;
                return true;
            }
            return false;
        } finally {
            mtx.unlock();
        }
    }
}


class FixedWindowStrategy implements RateLimitingStrategy {
    private final long windowCapacity;
    private final long windowInterval;
    private final Map<String, UserReqInfo> userReqInfoMap;

    FixedWindowStrategy(long windowCapacity, long windowInterval) {
        this.windowCapacity = windowCapacity;
        this.windowInterval = windowInterval;
        userReqInfoMap = new ConcurrentHashMap<>();
    }


    @Override
    public Boolean isAllowed(User user) {
        if (!userReqInfoMap.containsKey(user.getId())) {
            userReqInfoMap.put(user.getId(), new UserReqInfo(user.getId(), windowCapacity, windowInterval));
        }

        UserReqInfo userReqInfo = userReqInfoMap.get(user.getId());
        return userReqInfo.isRequestAllowed(user, System.currentTimeMillis());
    }
}


public class RateLimiter {
    private static RateLimiter instance;
    private static final Lock mtx = new ReentrantLock();
    private RateLimitingStrategy strategy;

    private RateLimiter(RateLimitingStrategy strategy) {
        this.strategy = strategy;
    }

    public static RateLimiter getInstance(RateLimitingStrategy strategy) {
        if (instance == null) {
            mtx.lock();
            try {
                instance = new RateLimiter(strategy);
            } finally {
                mtx.unlock();
            }
        }
        return instance;
    }

    public void setStrategy(RateLimitingStrategy strategy) {
        this.strategy = strategy;
    }

    public Boolean isAllowed(User user) {
        Boolean ans = this.strategy.isAllowed(user);
        if (ans) {
            System.out.println("Request is allowed for user " + user.getName());
        } else {
            System.out.println("Request is not allowed for user " + user.getName());
        }
        return ans;
    }


    public static void main() {
        String userId = UUID.randomUUID().toString();
        User user1 = new User(UUID.randomUUID().toString(), "Abhishek");
        User user2 = new User(UUID.randomUUID().toString(), "Khan");

        RateLimitingStrategy strategy1 = new FixedWindowStrategy(3, 5);
        RateLimiter rateLimiter = new RateLimiter(strategy1);
        rateLimiter.setStrategy(strategy1);
        rateLimiter.isAllowed(user1);
        rateLimiter.isAllowed(user1);
        rateLimiter.isAllowed(user1);
        rateLimiter.isAllowed(user1);
        try{
           Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        rateLimiter.isAllowed(user1);
    }
}
