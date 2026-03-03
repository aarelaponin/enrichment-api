package org.joget.gam.enrichment.api;

import java.util.ArrayList;
import java.util.Collection;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {

    private static long startTime;

    public static long getStartTime() {
        return startTime;
    }

    protected Collection<ServiceRegistration> registrationList;

    @Override
    public void start(BundleContext context) {
        startTime = System.currentTimeMillis();
        registrationList = new ArrayList<>();

        // Register the Enrichment API plugin
        registrationList.add(context.registerService(
                EnrichmentApiPlugin.class.getName(),
                new EnrichmentApiPlugin(),
                null
        ));
    }

    @Override
    public void stop(BundleContext context) {
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}
