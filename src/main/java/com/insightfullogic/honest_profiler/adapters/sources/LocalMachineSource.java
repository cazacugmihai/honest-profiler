package com.insightfullogic.honest_profiler.adapters.sources;

import com.insightfullogic.honest_profiler.core.conductor.Conductor;
import com.insightfullogic.honest_profiler.core.conductor.LogConsumer;
import com.insightfullogic.honest_profiler.core.conductor.MachineListener;
import com.insightfullogic.honest_profiler.core.conductor.ProfileListener;
import com.insightfullogic.honest_profiler.core.sources.VirtualMachine;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachineDescriptor;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class LocalMachineSource {

    private static final String VM_ARGS = "sun.jvm.args";
    private static final String AGENT_NAME = "liblagent.so";
    private static final String USER_DIR = "user.dir";

    private final MachineListener listener;
    private final Conductor conductor;
    private final ThreadedAgent threadedAgent;

    private Set<VirtualMachineDescriptor> previous;

    public LocalMachineSource(MachineListener listener, Conductor conductor) {
        this.listener = listener;
        this.conductor = conductor;
        previous = new HashSet<>();
        threadedAgent = new ThreadedAgent(this::discoverVirtualMachines);
    }

    @PostConstruct
    public void start() {
        threadedAgent.start();
    }

    public boolean discoverVirtualMachines() {
        poll();

        sleep();

        return true;
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    public void poll() {
        Set<VirtualMachineDescriptor> current = new HashSet<>(com.sun.tools.attach.VirtualMachine.list());
        difference(current, previous, machine -> {
            ProfileListener profileListener = listener.onNewMachine(machine);
            if (machine.isAgentLoaded()) {
                try {
                    LogConsumer logConsumer = conductor.pipeFile(machine.getLogFile(), machine, profileListener);
                    new ThreadedAgent(logConsumer::run).start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        difference(previous, current, listener::onClosedMachine);
        previous = current;
    }

    private void difference(
            Set<VirtualMachineDescriptor> left,
            Set<VirtualMachineDescriptor> right,
            Consumer<VirtualMachine> action) {

        left.stream()
            .filter(vm -> !right.contains(vm))
            .flatMap(this::attach)
            .forEach(action);
    }

    private Stream<VirtualMachine> attach(VirtualMachineDescriptor vmDescriptor) {
        try {
            com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(vmDescriptor);
            Properties agentProperties = vm.getAgentProperties();
            String vmArgs = agentProperties.getProperty(VM_ARGS);

            String id = vmDescriptor.id();
            String displayName = vmDescriptor.displayName();
            boolean agentLoaded = vmArgs.contains(AGENT_NAME);
            String userDir = agentProperties.getProperty(USER_DIR);

            return Stream.of(new VirtualMachine(id, displayName, agentLoaded, userDir));
        } catch (AttachNotSupportedException e) {
            System.err.println(e.getMessage());
        } catch (IOException e) {
            if (!noSuchProcess(e)) {
                e.printStackTrace();
            }
        }
        return Stream.empty();
    }

    private boolean noSuchProcess(IOException e) {
        return e.getMessage().contains("No such process");
    }

    @PreDestroy
    public void stop() {
        threadedAgent.stop();
    }

}
