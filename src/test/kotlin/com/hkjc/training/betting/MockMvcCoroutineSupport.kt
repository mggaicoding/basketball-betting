package com.hkjc.training.betting

import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request

fun MockMvc.performSuspending(requestBuilder: MockHttpServletRequestBuilder): ResultActions {
    val initialResult =
        perform(requestBuilder)
            .andExpect(request().asyncStarted())
            .andReturn()
    return perform(asyncDispatch(initialResult))
}
