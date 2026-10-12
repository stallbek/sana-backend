package com.is.aigul.config;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class BoundedRequestWrapperTest {
    @Test void limitsTheStreamWithoutTrustingContentLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[]{1,2,3,4});
        var input = new BoundedRequestWrapper(request, 3).getInputStream();
        assertThat(input.readNBytes(3)).containsExactly((byte)1, (byte)2, (byte)3);
        assertThatThrownBy(input::read).isInstanceOf(IOException.class);
    }
}
