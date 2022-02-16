package org.glavo.jmod.fallback.jlink;

import jdk.tools.jlink.internal.plugins.*;
import jdk.tools.jlink.plugin.Plugin;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;

import java.util.EnumSet;
import java.util.Set;

public class FallbackJmodPlugin extends AbstractPlugin {
    public FallbackJmodPlugin() {
        super("fallback-jmod");
    }

    @Override
    public Category getType() {
        return Category.FILTER;
    }

    @Override
    public Set<State> getState() {
        return EnumSet.of(State.AUTO_ENABLED, State.FUNCTIONAL);
    }

    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public String getOption() {
        return "fallback-jmod";
        //return "runtime-path";
    }

    @Override
    public String getDescription() {
        return "JUST FOR TEST";
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        in.transformAndCopy(resource -> {
            return resource;
        }, out);
        return out.build();
    }
}
