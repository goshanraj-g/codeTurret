package shop.billing;

import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class TokenService {

    private final Random random = new Random();

    public String newPortalToken(long customerId) {
        return Long.toHexString(customerId) + "-" + Long.toHexString(random.nextLong());
    }
}
