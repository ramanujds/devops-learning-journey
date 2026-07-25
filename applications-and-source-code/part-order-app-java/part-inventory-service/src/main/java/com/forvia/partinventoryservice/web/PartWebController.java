package com.forvia.partinventoryservice.web;

import com.forvia.partinventoryservice.model.Part;
import com.forvia.partinventoryservice.repository.PartRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Optional;
import java.util.UUID;

@Controller
public class PartWebController {

    @Autowired
    private PartRepository partRepository;

    @GetMapping
    public String home(Model model) {
        model.addAttribute("parts", partRepository.findAll());
        model.addAttribute("part", new Part());
        return "index";
    }

    @GetMapping("/inventory")
    public String listParts(Model model) {
        model.addAttribute("parts", partRepository.findAll());
        model.addAttribute("part", new Part());
        return "index";
    }

    @GetMapping("/inventory-update")
    public String inventoryUpdate(Model model) {
        if (!model.containsAttribute("type")) model.addAttribute("type", "success");
        if (!model.containsAttribute("message")) model.addAttribute("message", "Operation completed.");
        if (!model.containsAttribute("nextUrl")) model.addAttribute("nextUrl", "/inventory");
        if (!model.containsAttribute("nextLabel")) model.addAttribute("nextLabel", "Back to Inventory");
        return "inventory-update";
    }

    @PostMapping("/parts")
    public String addPart(@ModelAttribute Part part, Model model) {
        part.setId(UUID.randomUUID().toString());
        partRepository.save(part);

        model.addAttribute("type", "success");
        model.addAttribute("message", "Part added successfully.");
        model.addAttribute("nextUrl", "/inventory");
        model.addAttribute("nextLabel", "Back to Inventory");
        return "inventory-update";
    }

    @GetMapping("/parts/{id}/edit")
    public String showEditForm(@PathVariable String id, Model model) {
        Optional<Part> part = partRepository.findById(id);
        if (part.isPresent()) {
            model.addAttribute("part", part.get());
            return "edit-part";
        }

        model.addAttribute("type", "error");
        model.addAttribute("message", "Part not found.");
        model.addAttribute("nextUrl", "/inventory");
        model.addAttribute("nextLabel", "Back to Inventory");
        return "inventory-update";
    }

    @PostMapping("/parts/{id}")
    public String updatePart(@PathVariable String id, @ModelAttribute Part part, Model model) {
        part.setId(id);
        partRepository.save(part);

        model.addAttribute("type", "success");
        model.addAttribute("message", "Part updated successfully.");
        model.addAttribute("nextUrl", "/inventory");
        model.addAttribute("nextLabel", "Back to Inventory");
        return "inventory-update";
    }

    @PostMapping("/parts/{id}/delete")
    public String deletePart(@PathVariable String id, Model model) {
        partRepository.deleteById(id);

        model.addAttribute("type", "success");
        model.addAttribute("message", "Part deleted successfully.");
        model.addAttribute("nextUrl", "/inventory");
        model.addAttribute("nextLabel", "Back to Inventory");
        return "inventory-update";
    }
}