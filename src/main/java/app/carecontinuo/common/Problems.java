package app.carecontinuo.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Problems {
    private Problems() {
    }

    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或无权访问。");
    }

    public static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权执行此操作。");
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
