/*
 * Copyright (c) 2012, Mayocat <hello@mayocat.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mayocat.shop.checkout.internal;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.core.UriInfo;

import org.mayocat.shop.billing.model.Address;
import org.mayocat.shop.billing.model.Customer;
import org.mayocat.shop.billing.model.Order;
import org.mayocat.shop.billing.store.AddressStore;
import org.mayocat.shop.billing.store.CustomerStore;
import org.mayocat.shop.billing.store.OrderStore;
import org.mayocat.shop.cart.CartAccessor;
import org.mayocat.shop.cart.model.Cart;
import org.mayocat.shop.catalog.model.Purchasable;
import org.mayocat.shop.checkout.CheckoutException;
import org.mayocat.shop.checkout.CheckoutRegister;
import org.mayocat.shop.checkout.CheckoutResponse;
import org.mayocat.shop.checkout.CheckoutSettings;
import org.mayocat.shop.checkout.RegularCheckoutException;
import org.mayocat.shop.checkout.front.CheckoutResource;
import org.mayocat.shop.payment.BaseOption;
import org.mayocat.shop.payment.GatewayException;
import org.mayocat.shop.payment.GatewayFactory;
import org.mayocat.shop.payment.Option;
import org.mayocat.shop.payment.PaymentGateway;
import org.mayocat.shop.payment.GatewayResponse;
import org.mayocat.shop.payment.model.PaymentOperation;
import org.mayocat.shop.payment.store.PaymentOperationStore;
import org.mayocat.shop.shipping.ShippingService;
import org.mayocat.shop.shipping.model.Carrier;
import org.mayocat.store.EntityAlreadyExistsException;
import org.mayocat.store.EntityDoesNotExistException;
import org.mayocat.store.InvalidEntityException;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @version $Id$
 */
@Component
public class DefaultCheckoutRegister implements CheckoutRegister
{
    @Inject
    private Logger logger;

    @Inject
    private CheckoutSettings checkoutSettings;

    @Inject
    private Provider<OrderStore> orderStore;

    @Inject
    private Provider<CustomerStore> customerStore;

    @Inject
    private Provider<AddressStore> addressStore;

    @Inject
    private Map<String, GatewayFactory> gatewayFactories;

    @Inject
    private Provider<PaymentOperationStore> paymentOperationStore;

    @Inject
    private ShippingService shippingService;

    @Inject
    private CartAccessor cartAccessor;

    @Override
    public CheckoutResponse checkout(final Cart cart, UriInfo uriInfo, Customer customer, Address deliveryAddress,
            Address billingAddress) throws CheckoutException
    {
        Preconditions.checkNotNull(customer);
        Order order;

        try {
            UUID customerId;
            UUID deliveryAddressId = null;
            UUID billingAddressId = null;
            Map<String, Object> data = Maps.newHashMap(); // Order JSON data

            customer.setSlug(customer.getEmail());
            if (this.customerStore.get().findBySlug(customer.getEmail()) == null) {
                customer = this.customerStore.get().create(customer);
            } else {
                customer = this.customerStore.get().findBySlug(customer.getEmail());
            }
            customerId = customer.getId();

            if (deliveryAddress != null) {
                deliveryAddress = this.addressStore.get().create(deliveryAddress);
                deliveryAddressId = deliveryAddress.getId();
            }
            if (billingAddress != null) {
                billingAddress = this.addressStore.get().create(billingAddress);
                billingAddressId = billingAddress.getId();
            }

            order = new Order();
            order.setBillingAddressId(billingAddressId);
            order.setDeliveryAddressId(deliveryAddressId);
            order.setCustomerId(customerId);

            // Items
            Long numberOfItems = 0l;
            final Map<Purchasable, Long> items = cart.getItems();
            List<Map<String, Object>> orderItems = Lists.newArrayList();
            for (final Purchasable p : items.keySet()) {
                numberOfItems += items.get(p);
                orderItems.add(new HashMap<String, Object>()
                {
                    {
                        put("type", "product");
                        put("id", p.getId());
                        put("title", p.getTitle());
                        put("quantity", items.get(p));
                        put("unitPrice", p.getUnitPrice());
                        put("itemTotal", p.getUnitPrice().multiply(BigDecimal.valueOf(items.get(p))));
                    }
                });
            }
            order.setNumberOfItems(numberOfItems);
            order.setItemsTotal(cart.getItemsTotal());
            data.put(Order.ORDER_DATA_ITEMS, orderItems);

            // Shipping
            if (cart.getSelectedShippingOption() != null) {
                final Carrier carrier = shippingService.getCarrier(cart.getSelectedShippingOption().getCarrierId());
                order.setShipping(cart.getSelectedShippingOption().getPrice());
                data.put(Order.ORDER_DATA_SHIPPING, new HashMap<String, Object>()
                {
                    {
                        put("carrierId", carrier.getId());
                        put("title", carrier.getTitle());
                        put("strategy", carrier.getStrategy());
                    }
                });
            }

            // Dates, currency, status
            order.setCreationDate(new Date());
            order.setUpdateDate(order.getCreationDate());
            order.setCurrency(cart.getCurrency());
            order.setStatus(Order.Status.NONE);

            // Grand total
            order.setGrandTotal(cart.getTotal());

            // JSON data
            order.setOrderData(data);

            order = orderStore.get().create(order);
        } catch (EntityAlreadyExistsException e1) {
            throw new CheckoutException(e1);
        } catch (InvalidEntityException e2) {
            throw new CheckoutException(e2);
        }

        String defaultGatewayFactory = checkoutSettings.getDefaultPaymentGateway();

        // Right now only the default gateway factory is supported.
        // In the future individual tenants will be able to setup their own payment gateway.

        if (!gatewayFactories.containsKey(defaultGatewayFactory)) {
            throw new CheckoutException("No gateway factory is available to handle the checkout.");
        }

        GatewayFactory factory = gatewayFactories.get(defaultGatewayFactory);
        PaymentGateway gateway = factory.createGateway();
        if (gateway == null) {
            throw new CheckoutException("Gateway could not be created.");
        }

        Map<Option, Object> options = Maps.newHashMap();
        options.put(BaseOption.BASE_URL, uriInfo.getBaseUri().toString());
        options.put(BaseOption.CANCEL_URL, uriInfo.getBaseUri() + CheckoutResource.PATH + "/" + order.getId() + "/" +
                CheckoutResource.PAYMENT_CANCEL_PATH);
        options.put(BaseOption.RETURN_URL,
                uriInfo.getBaseUri() + CheckoutResource.PATH + "/" + CheckoutResource.PAYMENT_RETURN_PATH);
        options.put(BaseOption.CURRENCY, cart.getCurrency());
        options.put(BaseOption.ORDER_ID, order.getId());

        try {
            CheckoutResponse response = new CheckoutResponse();
            GatewayResponse gatewayResponse = gateway.purchase(cart.getTotal(), options);

            if (gatewayResponse.isSuccessful()) {

                if (gatewayResponse.isRedirection()) {
                    response.setRedirectURL(Optional.fromNullable(gatewayResponse.getRedirectURL()));
                }

                cart.empty();
                cartAccessor.setCart(cart);

                if (gatewayResponse.getOperation().getResult().equals(PaymentOperation.Result.CAPTURED)) {
                    order.setStatus(Order.Status.PAID);
                } else {
                    order.setStatus(Order.Status.PAYMENT_PENDING);
                }

                try {
                    orderStore.get().update(order);
                    PaymentOperation operation = gatewayResponse.getOperation();
                    operation.setOrderId(order.getId());
                    paymentOperationStore.get().create(operation);
                } catch (EntityDoesNotExistException e) {
                    this.logger.error("Order error while checking out cart", e);
                    throw new CheckoutException(e);
                } catch (InvalidEntityException e) {
                    this.logger.error("Order error while checking out cart", e);
                    throw new CheckoutException(e);
                } catch (EntityAlreadyExistsException e) {
                    this.logger.error("Order error while checking out cart", e);
                    throw new CheckoutException(e);
                }

                return response;
            } else {
                throw new CheckoutException("Payment was not successful");
            }
        } catch (GatewayException e) {
            this.logger.error("Payment error while checking out cart", e);
            throw new CheckoutException(e);
        }
    }

    @Override
    public void dropOrder(UUID orderId) throws CheckoutException
    {
        Order order = orderStore.get().findById(orderId);
        if (order == null) {
            throw new RegularCheckoutException("Order with id [" + orderId.toString() + "] does not exist.");
        }
        try {
            orderStore.get().delete(order);
        } catch (EntityDoesNotExistException e) {
            throw new CheckoutException(e);
        }
    }

    @Override
    public boolean requiresForm()
    {
        return true;
    }
}
