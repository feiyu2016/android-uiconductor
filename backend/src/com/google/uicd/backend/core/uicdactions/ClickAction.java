// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.uicd.backend.core.uicdactions;

import com.google.uicd.backend.core.constants.StrategyType;
import com.google.uicd.backend.core.devicesdriver.AndroidDeviceDriver;
import com.google.uicd.backend.core.exceptions.UicdDeviceHttpConnectionResetException;
import com.google.uicd.backend.core.xmlparser.NodeContext;
import com.google.uicd.backend.core.xmlparser.Position;
import com.google.uicd.backend.core.xmlparser.XmlHelper;
import java.util.List;

/** ClickAction */
public class ClickAction extends BaseAction {

  // need by the jackson to deserialize
  public ClickAction() {}
  // raw XY
  public ClickAction(NodeContext nodeContext, boolean isDoubleClick) {
    this.nodeContext = nodeContext;
    if (nodeContext != null) {
      this.setName(nodeContext.getDisplayEstimate());
    }
    this.isDoubleClick = isDoubleClick;
  }

  public NodeContext nodeContext;
  private boolean isRawXY = false;
  private boolean isDoubleClick = false;

  private boolean isByElement;
  private StrategyType strategy;
  private String selector;

  @Override
  public void updateAction(BaseAction baseAction) {
    super.updateBaseAction(baseAction);
    if (baseAction instanceof ClickAction) {
      ClickAction otherAction = (ClickAction) baseAction;
      this.isRawXY = otherAction.isRawXY;
      this.strategy = otherAction.strategy;
      this.selector = otherAction.selector;
    }
  }

  @Override
  public String getDisplay() {
    if (isByElement) {
      return String.format("%s - %s", strategy, selector);
    }
    String clickedPos = nodeContext == null ? "" : nodeContext.getClickedPos().toString();
    if (isRawXY) {
      return clickedPos;
    } else {
      return String.format("%s, %s", this.getName(), clickedPos);
    }
  }

  @Override
  protected int play(AndroidDeviceDriver androidDeviceDriver, ActionContext actionContext)
      throws UicdDeviceHttpConnectionResetException {
    if (isRawXY) {
      androidDeviceDriver.clickDevice(
          (int) nodeContext.getClickedPos().x, (int) nodeContext.getClickedPos().y);
      return 0;
    }
    boolean withClassName = isByElement;
    List<String> xmls = androidDeviceDriver.fetchCurrentXML(withClassName);
    if (isByElement) {
      String clickedText =
          actionContext.expandUicdGlobalVariable(selector, androidDeviceDriver.getDeviceId());
      androidDeviceDriver.clickByElement(xmls, strategy, clickedText,
          androidDeviceDriver.getWidthRatio(), androidDeviceDriver.getHeightRatio(), isDoubleClick);
    } else {
      logger.config(String.format("XML from device, size: %d", xmls.size()));
      Position pos =
          XmlHelper.getPosFromContextXML(
              xmls,
              this.nodeContext,
              androidDeviceDriver.getWidthRatio(),
              androidDeviceDriver.getHeightRatio());
      logger.info(String.format("Position from xml engine: (x:%f, y:%f)", pos.x, pos.y));
      androidDeviceDriver.clickDevice((int) pos.x, (int) pos.y, isDoubleClick);
    }

    return 0;
  }
}
