package biz.thonbecker.personal.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

class NullableInjectionTest {
    private static final Set<Class<?>> OPTIONAL =
            Set.of(ChatClient.Builder.class, VectorStore.class, BedrockRuntimeClient.class);

    @Test
    void servicesStartWithoutOptionalDependencies() throws Exception {
        final var classes = new String[] {
            "biz.thonbecker.personal.budget.platform.BudgetChatService",
            "biz.thonbecker.personal.landscape.platform.service.LandscapeImageGenerationService",
            "biz.thonbecker.personal.skatetricks.platform.BedrockTrickAnalyzer",
            "biz.thonbecker.personal.skatetricks.platform.SkateTricksService",
            "biz.thonbecker.personal.trivia.platform.FinancialPeaceQuestionGenerator"
        };
        for (final var name : classes) {
            final var type = Class.forName(name);
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        final var factory = ((GenericApplicationContext) context).getDefaultListableBeanFactory();
                        for (final var parameter : type.getDeclaredConstructors()[0].getParameterTypes()) {
                            if (OPTIONAL.contains(parameter) || parameter == String.class) {
                                continue;
                            }
                            factory.registerSingleton(parameter.getName(), mock(parameter));
                        }
                        factory.registerBeanDefinition("subject", new RootBeanDefinition(type));
                    })
                    .run(context -> assertThat(context).as(name).hasNotFailed().hasSingleBean(type));
        }
    }
}
