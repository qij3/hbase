/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.tmpl.master.MasterStatusTmpl;
import org.apache.hadoop.hbase.util.FSUtils;

import com.google.protobuf.ServiceException;

/**
 * The servlet responsible for rendering the index page of the
 * master.
 */
@InterfaceAudience.Private
public class MasterStatusServlet extends HttpServlet {
  private static final Log LOG = LogFactory.getLog(MasterStatusServlet.class);
  private static final long serialVersionUID = 1L;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response)
    throws IOException
  {
    HMaster master = (HMaster) getServletContext().getAttribute(HMaster.MASTER);
    assert master != null : "No Master in context!";

    response.setContentType("text/html");

    if (!master.isOnline()) {
      response.getWriter().write("The Master is initializing!");
      response.getWriter().close();
      return;
    }

    Configuration conf = master.getConfiguration();
    HBaseAdmin admin = new HBaseAdmin(conf);

    Map<String, Integer> frags = getFragmentationInfo(master, conf);
    ServerName metaLocation = null;
    List<ServerName> servers = null;
    Set<ServerName> deadServers = null;
    
    if(master.isActiveMaster()) {
      if (!master.isInitialized()) {
        response.sendError(503, "Master not ready");
        return;
      }
      metaLocation = getMetaLocationOrNull(master);
      //ServerName metaLocation = master.getCatalogTracker().getMetaLocation();
      servers = master.getServerManager().getOnlineServersList();
      deadServers = master.getServerManager().getDeadServers().copyServerNames();
    }

    MasterStatusTmpl tmpl;
    try {
       tmpl = new MasterStatusTmpl()
      .setFrags(frags)
      .setMetaLocation(metaLocation)
      .setServers(servers)
      .setDeadServers(deadServers)
      .setCatalogJanitorEnabled(master.getMasterRpcServices().isCatalogJanitorEnabled(
          null, RequestConverter.buildIsCatalogJanitorEnabledRequest()).getValue());
    } catch (ServiceException s) {
      admin.close();
      throw new IOException(s);
    }
    if (request.getParameter("filter") != null)
      tmpl.setFilter(request.getParameter("filter"));
    if (request.getParameter("format") != null)
      tmpl.setFormat(request.getParameter("format"));
    tmpl.render(response.getWriter(),
          master, admin);
  }

  private ServerName getMetaLocationOrNull(HMaster master) {
    try {
      return (master.getCatalogTracker() == null) ? null : master.getCatalogTracker().getMetaLocation();
    } catch (InterruptedException e) {
      LOG.warn("Unable to get meta location", e);
      return null;
    }
  }

  private Map<String, Integer> getFragmentationInfo(
      HMaster master, Configuration conf) throws IOException {
    boolean showFragmentation = conf.getBoolean(
        "hbase.master.ui.fragmentation.enabled", false);
    if (showFragmentation) {
      return FSUtils.getTableFragmentation(master);
    } else {
      return null;
    }
  }
}
