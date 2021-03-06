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

package com.google.uicd.backend.core.xmlparser;

import com.google.common.collect.HashBiMap;
import com.google.uicd.backend.core.constants.UicdConstant;
import com.google.uicd.backend.core.exceptions.UicdXMLFormatException;
import com.google.uicd.backend.core.utils.UicdCoreDelegator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

/** Parses UI XML string and get NodeContext of a Node */
public class XmlParser {

  // In the webview, the elements not in the current view are still in the XML dump, with a very
  // small height value. e.g. In the quick search, we saw lots of elements bounds are similar to
  // this: [28,2541][1414,2544]. Use this threshold to filter out those items, otherwise
  // ScrollScreenContentValidationAction sometimes doesn't work.
  private static final int ELEMENT_MIN_WIDTH_HEIGHT_THRESHOLD = 10;
  private static final double FULL_SCREEN_NODE_SIZE_THRESHOLD = 0.8;

  public HashBiMap<Bounds, NodeContext> boundsElementHashBiMap = HashBiMap.create();
  private double xRatio;
  private double yRatio;

  private final List<NodeContext> nodeContextsList = new ArrayList<>();
  private final HashMap<String, Integer> resourceIdCntMap = new HashMap<>();

  public XmlParser(List<String> xmls, double xRatio, double yRatio) {
    Document doc;
    List<Element> xmlElementList = new ArrayList<>();
    try {
      for (String xml : xmls) {
        doc = DocumentHelper.parseText(xml);
        xmlElementList.add(doc.getRootElement());
      }
      this.xRatio = xRatio;
      this.yRatio = yRatio;
      initBounds(xmlElementList);
      updateAdditionNodeContextTreeInfo();
    } catch (Exception e) {
      UicdCoreDelegator.getInstance().logException(e);
    }
  }

  public Optional<NodeContext> findNodeContextByResourceIdAndBounds(
      TextValidator textValidator, Bounds targetBounds, int threshold) {
    return nodeContextsList.stream()
        .filter(
            x ->
                textValidator.isMatch(x.getResourceId())
                    && x.getBounds().getCenter().getDistance(targetBounds.getCenter()) < threshold)
        .findFirst();
  }

  public Optional<NodeContext> findNodeContextByTextValidatorAndBounds(
      TextValidator textValidator, Bounds targetBounds, int threshold) {
    sortByLayerAndBounds(nodeContextsList);
    return nodeContextsList.stream()
        .filter(
            x ->
                (textValidator.isMatch(x.getText()) || textValidator.isMatch(x.getContentDesc()))
                    && x.getBounds().getCenter().getDistance(targetBounds.getCenter()) < threshold)
        .findFirst();
  }

  public Optional<NodeContext> findNodeContextByTextValidator(TextValidator textValidator) {
    return nodeContextsList.stream()
        .filter(
            x -> textValidator.isMatch(x.getText()) || textValidator.isMatch(x.getContentDesc()))
        .findFirst();
  }

  public NodeContext findNodeContextByText(String text) {
    sortByLayerAndBounds(nodeContextsList);
    Optional<NodeContext> res =
        nodeContextsList.stream()
            .filter(
                x ->
                    x.getText().equalsIgnoreCase(text) || x.getContentDesc().equalsIgnoreCase(text))
            .findFirst();
    return res.orElse(null);
  }

  public Optional<NodeContext> findSmallestNode(List<NodeContext> nodeContexts, Position pos) {
    List<NodeContext> filteredNodes = filterByPosition(nodeContexts, pos);
    if (filteredNodes.isEmpty()) {
      return Optional.empty();
    }
    sortByLayerAndBounds(filteredNodes);
    int minLayerIndex = filteredNodes.get(filteredNodes.size() - 1).getXmlLayerIndex();
    // To make sure the first node is not the "full screen" container.
    // Check first node of each layer to see whether it matches. The reason of the following logic:
    // some layer's root container is full screen, however real content of the layer is only part of
    // the screen. e.g. soft Keyboard. Without the following logic it will match the wrong layer
    // which will cause issues later.
    for (int i = filteredNodes.get(0).getXmlLayerIndex(); i >= minLayerIndex; i--) {
      final int currentLayer = i;
      Optional<NodeContext> currentLayerFirstNode =
          filteredNodes.stream().filter(n -> n.getXmlLayerIndex() == currentLayer).findFirst();
      if (currentLayerFirstNode.isPresent()
          && isQualifiedSmallestNode(currentLayerFirstNode.get())) {
        return currentLayerFirstNode;
      }
    }
    return Optional.empty();
  }

  // find area that contains x, y
  private List<NodeContext> filterByPosition(List<NodeContext> nodeContexts, Position pos) {
    List<NodeContext> filteredNodeContextList = new ArrayList<>();
    for (NodeContext nodeContext : nodeContexts) {
      Bounds bounds = nodeContext.getBounds();
      if (!bounds.isInCurrentBounds(pos)) {
        continue;
      }
      filteredNodeContextList.add(nodeContext);
    }
    return filteredNodeContextList;
  }

  public boolean hasAttrValue(Element node, String attrName) {
    return node.attribute(attrName) != null && !node.attribute(attrName).getValue().isEmpty();
  }

  public boolean nodeAttributeValueEquals(Element node, String attrName, String attrVal) {
    return hasAttrValue(node, attrName) && node.attribute(attrName).getValue().equals(attrVal);
  }

  public boolean isTextNode(Element node) {
    return !getNodeAttrAsString(node, UicdConstant.PROPERTY_NAME_TEXT).isEmpty()
        || !getNodeAttrAsString(node, UicdConstant.PROPERTY_NAME_CONTENT_DESCRIPTION).isEmpty();
  }

  // return text or toggle button.
  public Optional<NodeContext> findLowestMeaningfulNode(Optional<NodeContext> nodeContext) {
    if (!nodeContext.isPresent()) {
      return nodeContext;
    }
    NodeContext curNode = nodeContext.get();
    NodeContext candidateNode = curNode;
    while (curNode != null) {
      // If the search area is greater than 25% of the screen size, we think it is too big and too
      // much noise.
      if (curNode.getBounds().areaSize() > (Bounds.getFullScreenBounds().areaSize() / 4.0)
          && candidateNode.getCountVal() >= 1) {
        break;
      }
      if (curNode.getCountVal() > 10) {
        return Optional.of(candidateNode);
      }
      if (curNode.getDepthVal() >= 2) {
        return Optional.of(curNode);
      } else if (curNode.getCountVal() >= 1) {
        candidateNode = curNode;
      }
      curNode = curNode.getParentNode();
    }
    return Optional.of(candidateNode);
  }

  // override sortedBounds?
  public Optional<NodeContext> findLowestClickableNode(Optional<NodeContext> nodeContext) {
    NodeContext curNode = nodeContext.get();
    while (curNode != null) {
      if (curNode.isClickableNode()) {
        return Optional.of(curNode);
      }
      if (curNode.getCountVal() >= 1) {
        return Optional.of(curNode);
      }
      curNode = curNode.getParentNode();
    }
    return Optional.empty();
  }

  // If current node's size is too big and it is some Layout Node, it won't be the valid smallest
  // node
  private boolean isQualifiedSmallestNode(NodeContext nodeContext) {
    return nodeContext.getBounds().areaSize()
            < (Bounds.getFullScreenBounds().areaSize() * FULL_SCREEN_NODE_SIZE_THRESHOLD)
        || !nodeContext.getClassName().contains("Layout");
  }

  private void sortByLayerAndBounds(List<NodeContext> nodeContexts) {
    Collections.sort(
        nodeContexts,
        Comparator.comparingInt(NodeContext::getXmlLayerIndex)
            .reversed()
            .thenComparingDouble((NodeContext n) -> n.getBounds().areaSize()));
  }

  /* convert xml node to nodeContext */
  private void initBounds(List<Element> roots) throws UicdXMLFormatException {
    int xmlLayerIndex = 0;
    for (Element root : roots) {
      initBoundsFromRoot(root, xmlLayerIndex++);
    }
  }

  private Optional<NodeContext> initBoundsFromRoot(Element xmlNode, int xmlLayerIndex)
      throws UicdXMLFormatException {
    @SuppressWarnings("unchecked") // safe covariant cast
    List<Element> elements = xmlNode.elements();
    List<NodeContext> childrenList = new ArrayList<>();
    for (Element elem : elements) {
      Optional<NodeContext> childNodeContext = initBoundsFromRoot(elem, xmlLayerIndex);
      childNodeContext.ifPresent(child -> childrenList.add(child));
    }

    Bounds bounds = new Bounds();
    NodeContext nodeContext = new NodeContext();
    nodeContext.setXmlLayerIndex(xmlLayerIndex);
    if (!getNodeAttrAsString(xmlNode, UicdConstant.PROPERTY_NAME_BOUNDS).isEmpty()) {
      bounds =
          Bounds.createBoundsFromString(
              getNodeAttrAsString(xmlNode, UicdConstant.PROPERTY_NAME_BOUNDS), xRatio, yRatio);
    }
    nodeContext.fromXmlNode(xmlNode, bounds);
    if (bounds.getHeight() < (ELEMENT_MIN_WIDTH_HEIGHT_THRESHOLD / yRatio)
        || bounds.getWidth() < (ELEMENT_MIN_WIDTH_HEIGHT_THRESHOLD / xRatio)) {
      return Optional.empty();
    }
    getNodeContextsList().add(nodeContext);
    // update the resId count map.
    if (xmlNode == null || xmlNode.attribute(UicdConstant.PROPERTY_NAME_RESOURCE_ID) == null) {
      return Optional.empty();
    }

    updateResourceIdCntMap(nodeContext.getResourceId());
    nodeContext.setChildren(childrenList);
    return Optional.of(nodeContext);
  }

  private void updateAdditionNodeContextTreeInfo() {
    for (NodeContext nodeContext : this.getNodeContextsList()) {
      nodeContext.setUniqueResourceId(isUniqueResourceId(nodeContext.getResourceId()));
    }
  }

  private String getNodeAttrAsString(Element node, String attrName) {
    if (node.attribute(attrName) == null) {
      return "";
    }
    return node.attribute(attrName).getValue();
  }

  private boolean updateResourceIdCntMap(String resId) {
    if (resId.isEmpty()) {
      return false;
    }

    boolean isResIdUnique = true;
    if (!resourceIdCntMap.containsKey(resId)) {
      resourceIdCntMap.put(resId, 0);
    } else {
      isResIdUnique = true;
    }
    resourceIdCntMap.put(resId, resourceIdCntMap.get(resId) + 1);
    return isResIdUnique;
  }

  public boolean isUniqueResourceId(String resId) {
    if (resId.isEmpty()) {
      return false;
    }
    // On the WebView mode, it will generate random resource-id every time, should not treat those
    // id as unique id, otherwise it won't match during the replay.
    if (resId.startsWith("uid_")) {
      return false;
    }
    return resourceIdCntMap.getOrDefault(resId, 0) <= 1;
  }

  public List<NodeContext> getNodeContextsList() {
    return nodeContextsList;
  }
}
