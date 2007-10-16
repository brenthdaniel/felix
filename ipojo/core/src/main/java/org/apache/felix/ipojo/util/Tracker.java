/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.ipojo.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

/**
 * Utility class close to the OSGi Service Tracker.
 * This class is used when tracking dynamic services is required.
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class Tracker implements TrackerCustomizer {

    /**
     * Bundle context against which this Tracker object is tracking.
     */
    protected final BundleContext m_context;

    /**
     * Filter specifying search criteria for the services to track.
     */
    protected Filter m_filter;

    /**
     * TrackerCustomizer object for this tracker.
     */
    final TrackerCustomizer m_customizer;

    /**
     * Filter string for use when adding the ServiceListener. If this field is set, then certain optimizations can be taken since we don't have a user supplied filter.
     */
    final String m_listenerFilter;

    /**
     * Class name to be tracked. If this field is set, then we are tracking by class name.
     */
    private final String m_trackClass;

    /**
     * Reference to be tracked. If this field is set, then we are tracking a single ServiceReference.
     */
    private final ServiceReference m_trackReference;

    /**
     * Tracked services: ServiceReference object -> customized. Object and ServiceListener object
     */
    private Tracked m_tracked;

    /**
     * Cached ServiceReference for getServiceReference. This field is volatile since it is accessed by multiple threads.
     */
    private volatile ServiceReference m_cachedReference;

    /**
     * Cached service object for getService. This field is volatile since it is accessed by multiple threads.
     */
    private volatile Object m_cachedService;

    /**
     * Create a Tracker object on the specified ServiceReference object.
     * The service referenced by the specified ServiceReference object will be tracked by this Tracker.
     * @param context BundleContext object against which the tracking is done.
     * @param reference ServiceReference object for the service to be tracked.
     * @param customizer The customizer object to call when services are added, modified, or removed in this Tracker object. If customizer is null, then this Tracker object will be used as
     *            the TrackerCustomizer object and the Tracker object will call the TrackerCustomizer methods on itself.
     */
    public Tracker(BundleContext context, ServiceReference reference, TrackerCustomizer customizer) {
        m_context = context;
        m_trackReference = reference;
        m_trackClass = null;
        if (customizer == null) {
            m_customizer = this;
        } else {
            m_customizer = customizer;
        }
        m_listenerFilter = "(" + Constants.SERVICE_ID + "=" + reference.getProperty(Constants.SERVICE_ID).toString() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try {
            this.m_filter = context.createFilter(m_listenerFilter);
        } catch (InvalidSyntaxException e) { // we could only get this exception if the ServiceReference was invalid
            throw new IllegalArgumentException("unexpected InvalidSyntaxException: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Create a Tracker object on the specified class name.
     * Services registered under the specified class name will be tracked by this Tracker object.
     * @param context BundleContext object against which the tracking is done.
     * @param clazz Class name of the services to be tracked.
     * @param customizer The customizer object to call when services are added, modified, or removed in this Tracker object. If customizer is null, then this Tracker object will be used as
     *            the TrackerCustomizer object and the Tracker object will call the TrackerCustomizer methods on itself.    
     */
    public Tracker(BundleContext context, String clazz, TrackerCustomizer customizer) {
        this.m_context = context;
        this.m_trackReference = null;
        this.m_trackClass = clazz;
        if (customizer == null) {
            m_customizer = this;
        } else {
            m_customizer = customizer;
        }
        this.m_listenerFilter = "(" + Constants.OBJECTCLASS + "=" + clazz.toString() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try {
            this.m_filter = context.createFilter(m_listenerFilter);
        } catch (InvalidSyntaxException e) { // we could only get this exception
            // if the clazz argument was
            // malformed
            throw new IllegalArgumentException("unexpected InvalidSyntaxException: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Create a Tracker object on the specified Filter object.
     * <p>
     * Services which match the specified Filter object will be tracked by this Tracker object.
     * @param context BundleContext object against which the tracking is done.
     * @param filter Filter object to select the services to be tracked.
     * @param customizer The customizer object to call when services are added, modified, or removed in this Tracker object. If customizer is null, then this Tracker object will be used as the
     *            TrackerCustomizer object and the Tracker object will call the TrackerCustomizer methods on itself.   
     */
    public Tracker(BundleContext context, Filter filter, TrackerCustomizer customizer) {
        this.m_context = context;
        this.m_trackReference = null;
        this.m_trackClass = null;
        this.m_listenerFilter = null;
        this.m_filter = filter;
        if (customizer == null) {
            m_customizer = this;
        } else {
            m_customizer = customizer;
        }
        if ((context == null) || (filter == null)) { // we throw a NPE here to be consistent with the other constructors
            throw new NullPointerException();
        }
    }

    /**
     * Open this Tracker object and begin tracking services.
     * <p>
     * Services which match the search criteria specified when this Tracker object was created are now tracked by this Tracker object.
     */
    public synchronized void open() {
        if (m_tracked != null) { return; }

        m_tracked = new Tracked();
        synchronized (m_tracked) {
            try {
                m_context.addServiceListener(m_tracked, m_listenerFilter);
                ServiceReference[] references;
                if (m_listenerFilter == null) { // user supplied filter
                    references = getInitialReferences(null, m_filter.toString());
                } else { // constructor supplied filter
                    if (m_trackClass == null) {
                        references = new ServiceReference[] { m_trackReference };
                    } else {
                        references = getInitialReferences(m_trackClass, null);
                    }
                }

                m_tracked.setInitialServices(references); // set tracked with
                // the initial
                // references
            } catch (InvalidSyntaxException e) {
                throw new RuntimeException("unexpected InvalidSyntaxException: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        /* Call tracked outside of synchronized region */
        m_tracked.trackInitialServices(); // process the initial references
    }

    /**
     * Returns the list of initial ServiceReference objects that will be tracked by this Tracker object.
     * @param trackClass the class name with which the service was registered, or null for all services.
     * @param filterString the filter criteria or null for all services.
     * @return the list of initial ServiceReference objects.
     * @throws InvalidSyntaxException if the filter uses an invalid syntax.
     */
    private ServiceReference[] getInitialReferences(String trackClass, String filterString) throws InvalidSyntaxException {
        return m_context.getServiceReferences(trackClass, filterString);
    }

    /**
     * Close this Tracker object.
     * <p>
     * This method should be called when this Tracker object should end the tracking of services.
     */
    public synchronized void close() {
        if (m_tracked == null) { return; }

        m_tracked.close();
        ServiceReference[] references = getServiceReferences();
        Tracked outgoing = m_tracked;

        try {
            m_context.removeServiceListener(outgoing);
        } catch (IllegalStateException e) {
            System.err.println("Context stopped");
            /* In case the context was stopped. */
        }
        if (references != null) {
            for (int i = 0; i < references.length; i++) {
                outgoing.untrack(references[i]);
            }
        }
        m_tracked = null;

    }

    /**
     * Default implementation of the TrackerCustomizer.addingService method.
     * <p>
     * This method is only called when this Tracker object has been constructed with a null TrackerCustomizer argument. The default implementation returns the result of calling getService,
     * on the BundleContext object with which this Tracker object was created, passing the specified ServiceReference object.
     * <p>
     * This method can be overridden in a subclass to customize the service object to be tracked for the service being added. In that case, take care not to rely on the default implementation of removedService that will unget the service.
     * @param reference Reference to service being added to this Tracker object.
     * @return The service object to be tracked for the service added to this Tracker object.
     * @see TrackerCustomizer
     */
    public boolean addingService(ServiceReference reference) {
        return true;
    }

    /**
     * Default implementation of the TrackerCustomizer.addedService method.  
     * @param reference added reference.
     * @see org.apache.felix.ipojo.util.TrackerCustomizer#addedService(org.osgi.framework.ServiceReference)
     */
    public void addedService(ServiceReference reference) {

    }

    /**
     * Default implementation of the TrackerCustomizer.modifiedService method.
     * <p>
     * This method is only called when this Tracker object has been constructed with a null TrackerCustomizer argument. The default implementation does nothing.
     * @param reference Reference to modified service.
     * @param service The service object for the modified service.
     * @see TrackerCustomizer
     */
    public void modifiedService(ServiceReference reference, Object service) {
    }

    /**
     * Default implementation of the TrackerCustomizer.removedService method.
     * <p>
     * This method is only called when this Tracker object has been constructed with a null TrackerCustomizer argument. The default implementation calls ungetService, on the
     * BundleContext object with which this Tracker object was created, passing the specified ServiceReference object.
     * <p>
     * This method can be overridden in a subclass. If the default implementation of addingService method was used, this method must unget the service.
     * @param reference Reference to removed service.
     * @param service The service object for the removed service.
     * @see TrackerCustomizer
     */
    public void removedService(ServiceReference reference, Object service) {
        m_context.ungetService(reference);
    }

    /**
     * Wait for at least one service to be tracked by this Tracker object.
     * <p>
     * It is strongly recommended that waitForService is not used during the calling of the BundleActivator methods. BundleActivator methods are expected to complete in a short period of time.
     * @param timeout time interval in milliseconds to wait. If zero, the method will wait indefinately.
     * @return Returns the result of getService().
     * @throws InterruptedException If another thread has interrupted the current thread.
     */
    public Object waitForService(long timeout) throws InterruptedException {
        if (timeout < 0) { throw new IllegalArgumentException("timeout value is negative"); }
        Object object = getService();
        while (object == null) {
            Tracked tracked = this.m_tracked; // use local var since we are not synchronized
            if (tracked == null) { /* if Tracker is not open */
                return null;
            }
            synchronized (tracked) {
                if (tracked.size() == 0) {
                    tracked.wait(timeout);
                }
            }
            object = getService();
            if (timeout > 0) { return object; }
        }
        return object;
    }

    /**
     * Return an array of ServiceReference objects for all services being tracked by this Tracker object.
     * @return Array of ServiceReference objects or null if no service are being tracked.
     */
    public ServiceReference[] getServiceReferences() {
        Tracked tracked = this.m_tracked; // use local var since we are not synchronized
        if (tracked == null) { // if Tracker is not open
            return null;
        }
        synchronized (tracked) {
            int length = tracked.size();
            if (length == 0) { return null; }
            ServiceReference[] references = new ServiceReference[length];
            Iterator keys = tracked.keySet().iterator();
            for (int i = 0; i < length; i++) {
                references[i] = (ServiceReference) keys.next();
            }
            // The resulting array is sorted by ranking.
            return references;
        }
    }

    /**
     * Get the list of stored service reference.
     * @return the list containing used service reference
     */
    public List/*<ServiceReference>*/getServiceReferencesList() {
        Tracked tracked = this.m_tracked; // use local var since we are not synchronized
        if (tracked == null) { // if Tracker is not open
            return null;
        }
        synchronized (tracked) {
            int length = tracked.size();
            if (length == 0) { return null; }
            List references = new ArrayList(length);
            Iterator keys = tracked.keySet().iterator();
            for (int i = 0; i < length; i++) {
                references.add(keys.next());
            }
            // The resulting array is sorted by ranking.
            return references;
        }
    }

    /**
     * Returns a ServiceReference object for one of the services being tracked by this Tracker object.
     * If multiple services are being tracked, the service with the highest ranking (as specified in its service.ranking property) is returned.
     * If there is a tie in ranking, the service with the lowest service ID (as specified in its service.id property); that is, the service that was registered first is returned.
     * This is the same algorithm used by BundleContext.getServiceReference.
     * @return ServiceReference object or null if no service is being tracked.
     * @since 1.1
     */
    public ServiceReference getServiceReference() {
        ServiceReference reference = m_cachedReference;
        if (reference != null) { return reference; }

        ServiceReference[] references = getServiceReferences();
        if (references == null) {
            return null;
        } else {
            // As the map is sorted, return the first element.
            return m_cachedReference = references[0];
        }
    }

    /**
     * Returns the service object for the specified ServiceReference object if the referenced service is being tracked by this Tracker object.
     * @param reference Reference to the desired service.
     * @return Service object or null if the service referenced by the specified ServiceReference object is not being tracked.
     */
    public Object getService(ServiceReference reference) {
        Tracked tracked = this.m_tracked; // use local var since we are not synchronized
        if (tracked == null) { /* if Tracker is not open */
            return null;
        }
        Object object = null;
        synchronized (tracked) {
            object = tracked.get(reference);
            if (object != null) { // The object was already get.
                return object;
            } else if (tracked.containsKey(reference)) { // Not already get
                object = m_context.getService(reference);
                tracked.put(reference, object);
                return object;
            }
            // Not already tracked.
            return m_context.getService(reference);
        }
    }

    /**
     * Unget the given service reference.
     * @param reference : service reference to unget.
     */
    public void ungetService(ServiceReference reference) {
        Tracked tracked = this.m_tracked; // use local var since we are not synchronized
        if (tracked == null) { /* if Tracker is not open */
            return;
        }
        Object object = null;
        synchronized (tracked) {
            object = tracked.get(reference);
        }
        if (object != null) {
            m_context.ungetService(reference);
        }
    }

    /**
     * Return an array of service objects for all services being tracked by this Tracker object.
     * @return Array of service objects or null if no service are being tracked.
     */
    public Object[] getServices() {
        Tracked tracked = this.m_tracked; // use local var since we are not synchronized
        if (tracked == null) { /* if Tracker is not open */
            return null;
        }
        synchronized (tracked) {
            ServiceReference[] references = getServiceReferences();
            int length = 0;
            if (references != null) {
                length = references.length;
            } else {
                return null;
            }
            Object[] objects = new Object[length];
            for (int i = 0; i < length; i++) {
                objects[i] = getService(references[i]);
            }
            return objects;
        }
    }

    /**
     * Returns a service object for one of the services being tracked by this Tracker object.
     * <p>
     * If any services are being tracked, this method returns the result of calling getService(getServiceReference()).
     * @return Service object or null if no service is being tracked.
     */
    public Object getService() {
        Object service = m_cachedService;
        if (service != null) { return service; }
        ServiceReference reference = getServiceReference();
        if (reference == null) { return null; }
        return m_cachedService = getService(reference);
    }

    /**
     * Remove a service from this Tracker object. The specified service will be removed from this Tracker object. If the specified service was being tracked then the
     * TrackerCustomizer.removedService method will be called for that service.
     * @param reference Reference to the service to be removed.
     */
    public void remove(ServiceReference reference) {
        Tracked tracked = this.m_tracked; // use local var since we are not synchronized
        if (tracked == null) { /* if Tracker is not open */
            return;
        }
        tracked.untrack(reference);
    }

    /**
     * Return the number of services being tracked by this Tracker object.
     * @return Number of services being tracked.
     */
    public int size() {
        Tracked tracked = this.m_tracked; //use local var since we are not synchronized
        if (tracked == null) { /* if Tracker is not open */
            return 0;
        }
        return tracked.size();
    }

    /**
     * Inner class to track services. If a Tracker object is reused (closed then reopened), then a new Tracked object is used. This class is a hashtable mapping ServiceReference object -> customized Object. This
     * class is the ServiceListener object for the tracker. This class is used to synchronize access to the tracked services. This is not a public class. It is only for use by the implementation of the Tracker
     * class.
     */
    class Tracked extends HashMap implements ServiceListener {
        /**
         * UID.
         */
        static final long serialVersionUID = -7420065199791006079L;

        /**
         * List of ServiceReferences in the process of being added. This is used to deal with nesting of ServiceEvents. Since ServiceEvents are synchronously delivered, ServiceEvents can be nested. For example, when processing the adding of a service
         * and the customizer causes the service to be unregistered, notification to the nested call to untrack that the service was unregistered can be made to the track method. Since the ArrayList implementation is not synchronized, all access to
         * this list must be protected by the same synchronized object for thread safety.
         */
        private List m_adding;

        /**
         * true if the tracked object is closed. This field is volatile because it is set by one thread and read by another.
         */
        private volatile boolean m_closed;

        /**
         * Initial list of ServiceReferences for the tracker. This is used to correctly process the initial services which could become unregistered before they are tracked. This is necessary since the initial set of tracked services are not
         * "announced" by ServiceEvents and therefore the ServiceEvent for unregistration could be delivered before we track the service. A service must not be in both the initial and adding lists at the same time. A service must be moved from the
         * initial list to the adding list "atomically" before we begin tracking it. Since the LinkedList implementation is not synchronized, all access to this list must be protected by the same synchronized object for thread safety.
         */
        private List m_initial;

        /**
         * Tracked constructor.
         */
        protected Tracked() {
            super();
            m_closed = false;
            m_adding = new ArrayList(6);
            m_initial = new LinkedList();
        }

        /**
         * Set initial list of services into tracker before ServiceEvents begin to be received. This method must be called from Tracker.open while synchronized on this object in the same synchronized block as the addServiceListener call.
         * @param references The initial list of services to be tracked.
         */
        protected void setInitialServices(ServiceReference[] references) {
            if (references == null) { return; }
            int size = references.length;
            for (int i = 0; i < size; i++) {
                m_initial.add(references[i]);
            }
        }

        /**
         * Track the initial list of services. This is called after ServiceEvents can begin to be received. This method must be called from Tracker.open while not synchronized on this object after the addServiceListener call.
         */
        protected void trackInitialServices() {
            while (true) {
                ServiceReference reference;
                synchronized (this) {
                    if (m_initial.size() == 0) { //  if there are no more inital services
                        return; // we are done
                    }

                    // move the first service from the initial list to the adding list within this synchronized block.
                    reference = (ServiceReference) ((LinkedList) m_initial).removeFirst();
                    if (this.containsKey(reference)) { //Check if the reference is already tracked.
                        //if we are already tracking this service
                        continue; /* skip this service */
                    }
                    if (m_adding.contains(reference)) {
                        // if this service is already in the process of being added.
                        continue; // skip this service
                    }
                    m_adding.add(reference);
                }
                trackAdding(reference); // Begin tracking it. We call trackAdding since we have already put the reference in the adding list.
            }
        }

        /**
         * Called by the owning Tracker object when it is closed.
         */
        protected void close() {
            m_closed = true;
        }

        /**
         * ServiceListener method for the Tracker class. This method must NOT be synchronized to avoid deadlock potential.
         * @param event ServiceEvent object from the framework.
         */
        public void serviceChanged(ServiceEvent event) {
            //Check if we had a delayed call (which could happen when we close).
            if (m_closed) { return; }
            ServiceReference reference = event.getServiceReference();

            switch (event.getType()) {
                case ServiceEvent.REGISTERED:
                case ServiceEvent.MODIFIED:
                    if (m_listenerFilter != null) { // constructor supplied filter
                        track(reference);
                    } else { // user supplied filter
                        if (m_filter.match(reference)) {
                            track(reference); // Arrival
                        } else {
                            untrack(reference); // Departure
                        }
                    }
                    break;
                case ServiceEvent.UNREGISTERING:
                    untrack(reference); // Departure
                    break;
                default:
                    break;
            }
        }

        /**
         * Begin to track the referenced service.
         * @param reference Reference to a service to be tracked.
         */
        protected void track(ServiceReference reference) {
            Object object;
            boolean alreadyTracked;
            synchronized (this) {
                alreadyTracked = this.containsKey(reference);
                object = this.get(reference);
            }
            if (alreadyTracked) { // we are already tracking the service
                if (object != null) { // If already get, invalidate the cache
                    synchronized (this) {
                        modified();
                    }
                }
                // Call customizer outside of synchronized region
                m_customizer.modifiedService(reference, object);
                return;
            }
            synchronized (this) {
                if (m_adding.contains(reference)) { // if this service is already in the process of being added.
                    return;
                }
                m_adding.add(reference); // mark this service is being added
            }

            trackAdding(reference); // call trackAdding now that we have put the reference in the adding list
        }

        /**
         * Common logic to add a service to the tracker used by track and trackInitialServices. The specified reference must have been placed in the adding list before calling this method.
         * @param reference Reference to a service to be tracked.
         */
        private void trackAdding(ServiceReference reference) {
            boolean mustBeTracked = false;
            boolean becameUntracked = false;
            boolean mustCallAdded = false;
            //Call customizer outside of synchronized region
            try {
                mustBeTracked = m_customizer.addingService(reference);
            } finally {
                synchronized (this) {
                    if (m_adding.remove(reference)) { // if the service was not untracked during the customizer callback
                        if (mustBeTracked) {
                            this.put(reference, null);
                            modified(); 
                            mustCallAdded = true;
                            notifyAll(); // notify any waiters in waitForService
                        }
                    } else {
                        becameUntracked = true;
                        // If already get during the customizer callback
                        ungetService(reference);
                        modified();
                    }
                }
            }

            // Call customizer outside of synchronized region
            if (becameUntracked) {
                // The service became untracked during the customizer callback.
                m_customizer.removedService(reference, null);
            } else {
                if (mustCallAdded) {
                    m_customizer.addedService(reference);
                }
            }
        }

        /**
         * Discontinue tracking the referenced service.
         * @param reference Reference to the tracked service.
         */
        protected void untrack(ServiceReference reference) {
            Object object;
            synchronized (this) {
                if (m_initial.remove(reference)) { // if this service is already in the list of initial references to process
                    return; // we have removed it from the list and it will not be processed
                }

                if (m_adding.remove(reference)) { // if the service is in the process of being added
                    return; // in case the service is untracked while in the process of adding
                }

                boolean isTraked = this.containsKey(reference); // Check if we was tracking the reference 
                object = this.remove(reference); // must remove from tracker before calling customizer callback

                if (!isTraked) { return; }
                modified();
            }
            // Call customizer outside of synchronized region
            m_customizer.removedService(reference, object);
            // If the customizer throws an unchecked exception, it is safe to let it propagate
        }

        /**
         * Called by the Tracked object whenever the set of tracked services is modified. Increments the tracking count and clears the cache.
         * This method must not be synchronized since it is called by Tracked while Tracked is synchronized. We don't want synchronization interactions between the ServiceListener thread and the user thread.
         */
        void modified() {
            m_cachedReference = null; /* clear cached value */
            m_cachedService = null; /* clear cached value */
        }
    }
}
