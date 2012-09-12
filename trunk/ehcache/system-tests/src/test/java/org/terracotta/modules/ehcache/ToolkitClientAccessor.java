/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.modules.ehcache;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheStoreAccessor;
import net.sf.ehcache.store.Store;

import org.terracotta.toolkit.Toolkit;

public class ToolkitClientAccessor {

  public static Toolkit getInternalToolkitClient(Cache cache) {
    CacheStoreAccessor storeAccessor = CacheStoreAccessor.newCacheStoreAccessor(cache);
    Store store = storeAccessor.getStore();
    Object internalContext = store.getInternalContext();
    if (!(internalContext instanceof ToolkitLookup)) throw new AssertionError(
                                                                              "Toolkit can only be looked up for Clustered Caches");
    return ((ToolkitLookup) internalContext).getToolkit();

  }

}