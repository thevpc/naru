package net.thevpc.naru.agent;

import net.thevpc.naru.api.Naru;
import net.thevpc.naru.api.NaruAgent;
import net.thevpc.naru.api.NaruSession;
import net.thevpc.naru.api.NaruTool;
import net.thevpc.naru.api.NaruToolParameter;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.task.NaruTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AgentModelIntegrationTest {

    private Naru naru;
    private NaruAgent agent;

    @BeforeEach
    void setUp() {
        // Initialize Naru system
        naru = Naru.get();
        agent = naru.createAgent("test-agent");
    }

    @Test
    public void testAgentModelInteraction() throws Exception {
        // Create a session
        NaruSession session = agent.createSession();
        
        // Create a simple tool that can be called by the model
        NaruTool tool = naru.createTool("test-tool")
                .description("A test tool for model interaction")
                .parameter(NaruToolParameter.builder()
                        .name("input")
                        .type(NaruToolParameter.Type.STRING)
                        .description("Input string to process")
                        .required(true)
                        .build())
                .parameter(NaruToolParameter.builder()
                        .name("count")
                        .type(NaruToolParameter.Type.INTEGER)
                        .description("Number of times to repeat")
                        .defaultValue(1)
                        .build())
                .build();
        
        // Register the tool
        session.registerTool(tool);
        
        // Create a task that will call the model
        NaruTask task = session.createTask();
        
        // Simulate model calling the tool
        String input = "Hello World";
        int count = 2;
        
        // Execute the tool
        Object result = tool.execute(task, Arrays.asList(input, count));
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result instanceof String);
        assertEquals("Hello WorldHello World", result);
        
        // Clean up
        session.close();
    }

    @Test
    public void testAgentSessionWithModelCall() throws Exception {
        // Create a session
        NaruSession session = agent.createSession();
        
        // Create a routine that represents a model call
        NaruRoutine routine = naru.createRoutine("test-model-routine")
                .description("A test routine that simulates model interaction")
                .build();
        
        // Register the routine
        session.registerRoutine(routine);
        
        // Create a task
        NaruTask task = session.createTask();
        
        // Simulate model interaction
        task.loadLines("Call test-tool with input='test' and count=3");
        
        // Execute the task
        task.run();
        
        // Get the result
        Object returnResult = task.getReturnResult();
        
        // Verify the result
        assertNotNull(returnResult);
        
        // Clean up
        session.close();
    }
}
