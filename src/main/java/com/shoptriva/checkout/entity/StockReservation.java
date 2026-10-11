package com.shoptriva.checkout.entity;

import com.shoptriva.common.entity.BaseEntity;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.order.entity.Order;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "stock_reservations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_order_inventory",
                        columnNames = {"order_id", "inventory_id"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "order_id",
            nullable = false
    )
    private Order order;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "inventory_id",
            nullable = false
    )
    private Inventory inventory;

    @Column(
            nullable = false
    )
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false
    )
    private ReservationStatus status;

    @Column(
            name = "expires_at",
            nullable = false
    )
    private Instant expiresAt;

    @Version
    private Long version;
}
