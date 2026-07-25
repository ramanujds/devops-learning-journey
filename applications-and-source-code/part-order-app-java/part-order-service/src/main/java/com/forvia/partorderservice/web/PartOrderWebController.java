package com.forvia.partorderservice.web;

import com.forvia.partorderservice.dto.OrderRequestDto;
import com.forvia.partorderservice.dto.OrderResponseDto;
import com.forvia.partorderservice.dto.PartDto;
import com.forvia.partorderservice.model.PartOrder;
import com.forvia.partorderservice.repository.PartOrderRepository;
import com.forvia.partorderservice.service.PartOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Controller
@Slf4j
public class PartOrderWebController {

    @Autowired
    private PartOrderService partOrderService;

    @Autowired
    private PartOrderRepository partOrderRepository;

    @GetMapping("/")
    public String homepage(Model model) {
        try {
            List<PartDto> parts = partOrderService.getAllAvailableParts();
            parts.forEach(System.out::println);
            model.addAttribute("parts", parts);
        } catch (Exception e) {
            log.error("Error fetching parts from inventory service", e);
            model.addAttribute("error", "Unable to fetch parts from inventory service. Please try again later.");
            model.addAttribute("parts", new ArrayList<>());
        }
        List<PartOrder> orders = partOrderRepository.findAll();
        model.addAttribute("orders", orders);
        return "home";
    }

    @GetMapping("/home")
    public String homepageForRoute(Model model) {
        try {
            List<PartDto> parts = partOrderService.getAllAvailableParts();
            parts.forEach(System.out::println);
            model.addAttribute("parts", parts);
        } catch (Exception e) {
            log.error("Error fetching parts from inventory service", e);
            model.addAttribute("error", "Unable to fetch parts from inventory service. Please try again later.");
            model.addAttribute("parts", new ArrayList<>());
        }
        List<PartOrder> orders = partOrderRepository.findAll();
        model.addAttribute("orders", orders);
        return "home";
    }

    @GetMapping("/orders")
    public String viewAllOrders(Model model) {
        List<PartOrder> orders = partOrderRepository.findAll();
        model.addAttribute("orders", orders);
        return "orders";
    }

    @GetMapping("/available-parts")
    public String browseAvailableParts(Model model) {
        try {
            List<PartDto> parts = partOrderService.getAllAvailableParts();
            model.addAttribute("parts", parts);
        } catch (Exception e) {
            log.error("Error fetching parts from inventory service", e);
            model.addAttribute("error", "Unable to fetch parts from inventory service. Please try again later.");
            model.addAttribute("parts", new ArrayList<>());
        }
        return "available-parts";
    }


    @GetMapping("/place-order")
    public String showPlaceOrderForm(@RequestParam(required = false) String sku, Model model) {
        OrderRequestDto dto = new OrderRequestDto(sku != null ? sku : "", 0);
        model.addAttribute("orderRequest", dto);
        return "place-order";
    }

    @PostMapping("/place-order")
    public String placeOrder(@ModelAttribute OrderRequestDto orderRequest, Model model) {
        try {
            OrderResponseDto response = partOrderService.placeOrder(orderRequest);
            PartOrder partOrder = new PartOrder();
            partOrder.setId(UUID.randomUUID().toString());
            partOrder.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            partOrder.setPartSku(response.partSku());
            partOrder.setQuantity(response.quantity());
            partOrder.setStatus("PLACED");
            partOrder.setPrice(response.totalPrice() > 0 ?
                java.math.BigDecimal.valueOf(response.totalPrice()) : null);
            partOrder.setTimestamp(LocalDateTime.now());
            partOrderRepository.save(partOrder);
            model.addAttribute("response", response);
        } catch (Exception e) {
            log.error("Error placing order", e);
            model.addAttribute("error", "Failed to place order. Please check the details and try again.");
        }
        return "order-result";
    }
}
