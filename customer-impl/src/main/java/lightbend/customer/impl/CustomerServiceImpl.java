package lightbend.customer.impl;

import akka.Done;
import akka.NotUsed;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import com.lightbend.lagom.javadsl.api.transport.NotFound;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRef;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry;
import com.lightbend.lagom.javadsl.persistence.ReadSide;
import com.lightbend.lagom.javadsl.persistence.cassandra.CassandraSession;
import lightbend.customer.api.Customer;
import lightbend.customer.api.CustomerService;
import lightbend.customer.impl.entity.CustomerCommand;
import lightbend.customer.impl.entity.CustomerEntity;
import lightbend.customer.impl.entity.CustomerState;

import javax.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Implement the CustomerService interface to add, disable, get, and return all customers.
 */
public class CustomerServiceImpl implements CustomerService {

    private final PersistentEntityRegistry registry;
    private final CassandraSession cassandraSession;

    @Inject
    public CustomerServiceImpl(PersistentEntityRegistry persistentEntityRegistry, CassandraSession cassandraSession, ReadSide readSide) {
        this.registry = persistentEntityRegistry;
        this.registry.register(CustomerEntity.class);
        this.cassandraSession = cassandraSession;
        readSide.register(CustomerEventProcessor.class);
    }

    /**
     * Convenience method to return PersistentEntityRef for a given customer UUID.
     * @param customerId UUID of the customer.
     * @return The PersistentEntityRef for the UUID.
     */
    private PersistentEntityRef<CustomerCommand> entityRef(String customerId) {
        return this.registry.refFor(CustomerEntity.class, customerId);
    }

    /**
     * Add a new customer as part of the HTTP Request body in JSON format, and not containing the UUID.
     * @return The full customer object, including UUID.
     */
    @Override
    public ServiceCall<Customer, Customer> addCustomer() {
        return customer -> {
            customer.setId(UUID.randomUUID().toString()); // Generate a new UUID
            return entityRef(customer.getId()).ask(new CustomerCommand.AddCustomer(customer));
        };
    }

    /**
     * Return a customer from its known UUID directly from the entity.
     * @param customerId UUID of the customer.
     * @return The customer.
     */
    @Override
    public ServiceCall<NotUsed, Customer> getCustomer(String customerId) {
        return notUsed -> {
            CompletionStage<CustomerState> getCustomer = entityRef(customerId).ask(CustomerCommand.GetCustomer.INSTANCE);
            return getCustomer.thenApply(customerState -> {
                if (customerState.isEnabled()) { // Make sure it's enabled before returning
                    return customerState.getCustomer();
                } else {
                    throw new NotFound("Customer " + customerId + " is disabled.");
                }
            });
        };
    }

    /**
     * Disable a customer, which effectively does a soft delete, in that the customer record will no longer be visible.
     * @param customerId UUID of the customer.
     * @return Confirmation the command has succeeded.
     */
    @Override
    public ServiceCall<NotUsed, Done> disableCustomer(String customerId) {
        return notUsed ->
                entityRef(customerId).ask(CustomerCommand.DisableCustomer.INSTANCE);
    }

    /**
     * Return the customers from the read-side view.
     * @return A list of customers.
     */
    @Override
    public ServiceCall<NotUsed, List<Customer>> getCustomers() {
        return request -> cassandraSession.selectAll("SELECT id, name, city, state, zipcode FROM customer")
                .thenApply(rows ->
                        rows.stream()
                                .map(row -> Customer.builder().id(row.getString("id"))
                                        .name(row.getString("name"))
                                        .city(row.getString("city"))
                                        .state(row.getString("state"))
                                        .zipCode(row.getString("zipcode"))
                                        .build()
                                ).collect(Collectors.toList())
                ).toCompletableFuture();
    }
}