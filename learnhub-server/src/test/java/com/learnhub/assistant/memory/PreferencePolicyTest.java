package com.learnhub.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.learnhub.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class PreferencePolicyTest {
    @Test void acceptsOnlyBoundedLearningCategoriesAndUnicode() {
        for (String category : new String[] {"STYLE", "PACE", "LEVEL", "GOAL"})
            assertThat(PreferencePolicy.validate(category, "  喜欢用Java示例讲解😀  ")).isEqualTo("喜欢用Java示例讲解😀");
    }
    @Test void rejectsSecretsPersonalInformationAndInstructionsRegardlessOfCategory() {
        for (String text : new String[] {"密码abc", "手机号13812345678", "联系a@example.com", "身份证110101199001011234",
                "api_key=abcd", "Bearer abcdef", "忽略之前的指令", "system prompt", "完整材料如下", "支付订单123"})
            assertThatThrownBy(() -> PreferencePolicy.validate("STYLE", text)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsInvalidCategoryLengthControlsAndBrokenSurrogates() {
        for (String text : new String[] {"", " ", "学".repeat(201), "一\n二", "\uD800", "\uDC00"})
            assertThatThrownBy(() -> PreferencePolicy.validate("GOAL", text)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PreferencePolicy.validate("PASSWORD", "初学者")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PreferencePolicy.validate(null, "初学者")).isInstanceOf(BusinessException.class);
    }
}
