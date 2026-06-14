package org.example;

import com.google.common.collect.ImmutableMap;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public class SimpleGraphApp {

    public static void main(String[] args) throws Exception {
        // Initialize nodes
        GreeterNode greeterNode = new GreeterNode();
        ResponderNode responderNode = new ResponderNode();

        // Define the graph structure
        StateGraph stateGraph = new StateGraph<>(MessageState.SCHEMA, initData -> new MessageState(initData))
                .addNode("greeter", node_async(greeterNode))
                .addNode("responder", node_async(responderNode))
                // Define edges
                .addEdge(START, "greeter") // Start with the greeter node
                .addEdge("greeter", "responder")
                .addEdge("responder", END)   // End after the responder node
                ;
        // Compile the graph
        CompiledGraph compiledGraph = stateGraph.compile();

        // Run the graph
        // The `stream` method returns an AsyncGenerator.
        // For simplicity, we'll collect results. In a real app, you might process them as they arrive.
        // Here, the final state after execution is the item of interest.

        for (Object item : compiledGraph.stream( ImmutableMap.of( MessageState.MESSAGES_KEY, "Let's, begin!" ) ) ) {

            System.out.println( item );
        }

    }
}
